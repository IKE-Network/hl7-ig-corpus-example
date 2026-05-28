package network.ike.examples.hl7ig.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/// HL7 IG-publisher package ingester.
///
/// Downloads FHIR NPM packages from the FHIR package registry, walks
/// each package's conformance resources (StructureDefinitions,
/// ValueSets, CodeSystems), and emits AsciiDoc topic fragments under
/// the corpus's `topics/ext/standards/{ig-slug}/` tree along with a
/// per-IG `topic-registry-shard-{ig-slug}.yaml`.
///
/// Invocation:
/// ```
/// mvn -pl ingest exec:java -Dexec.args="\
///   --package hl7.fhir.us.core@8.0.0 \
///   --package hl7.fhir.us.mcode@4.0.0 \
///   --package hl7.fhir.us.davinci-deqm@5.0.0 \
///   --out ../topics/src/docs/asciidoc/topics/ext/standards"
/// ```
///
/// Each emitted topic fragment carries the IKE-INGEST §"External
/// Source Ingestion" provenance attribute set:
///
///   :topic-provenance: external
///   :topic-citation:   HL7 International. {IG title}, version {v}. {url}
///   :topic-license:    Fair use summary of copyrighted work — not for redistribution.
///
/// Tracking issue: IKE-Network/ike-issues#549.
public final class IgPublisherIngester {

    private static final ObjectMapper JSON = new ObjectMapper();

    /// FHIR package registry base. `{base}/{packageId}/{version}` returns the package tarball directly.
    private static final String REGISTRY_BASE = "https://packages.fhir.org";

    private IgPublisherIngester() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            return;
        }

        List<PackageCoord> packages = collectPackageArgs(args);
        if (packages.isEmpty()) {
            System.err.println("error: at least one --package <name>@<version> required");
            System.exit(2);
        }

        Path outRoot = Path.of(requireArg(args, "--out"));
        Files.createDirectories(outRoot);

        for (PackageCoord coord : packages) {
            System.out.printf("%n=== Ingesting %s ===%n", coord);
            try {
                IngestStats stats = ingestPackage(coord, outRoot);
                System.out.printf("  ✓ %s: %d StructureDefinitions, %d ValueSets, %d CodeSystems%n",
                        coord.slug(),
                        stats.structureDefinitions, stats.valueSets, stats.codeSystems);
            } catch (Exception e) {
                System.err.printf("  ✗ %s: %s%n", coord, e.getMessage());
                throw e;
            }
        }
    }

    // ── Package coordinate parsing ───────────────────────────────────

    /// Parsed FHIR package coordinate.
    ///
    /// @param packageId  the dotted FHIR package id, e.g. `hl7.fhir.us.core`
    /// @param version    the version string, e.g. `8.0.0`
    record PackageCoord(String packageId, String version) {

        /// Short slug for output paths, derived from the last dotted
        /// segment of the package id (e.g. `us.core` → `us-core` becomes
        /// `core`; for `us.davinci-deqm` becomes `davinci-deqm`).
        String slug() {
            String last = packageId.substring(packageId.lastIndexOf('.') + 1);
            // For us.core and us.mcode, use the second-to-last segment too
            // to disambiguate (us-core, us-mcode) from any future ig with
            // the same trailing segment.
            String[] parts = packageId.split("\\.");
            if (parts.length >= 3 && "us".equals(parts[parts.length - 2])) {
                return "us-" + last;
            }
            return last;
        }

        @Override public String toString() {
            return packageId + "@" + version;
        }

        static PackageCoord parse(String arg) {
            int at = arg.indexOf('@');
            if (at < 0) {
                throw new IllegalArgumentException(
                        "Package coord must be name@version; got " + arg);
            }
            return new PackageCoord(arg.substring(0, at), arg.substring(at + 1));
        }
    }

    private static List<PackageCoord> collectPackageArgs(String[] args) {
        List<PackageCoord> out = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if ("--package".equals(args[i])) {
                out.add(PackageCoord.parse(args[i + 1]));
            }
        }
        return out;
    }

    // ── Ingestion entry point ────────────────────────────────────────

    record IngestStats(int structureDefinitions, int valueSets, int codeSystems) {
        int total() { return structureDefinitions + valueSets + codeSystems; }
    }

    /// Ingest one IG package: download, extract, walk, emit.
    static IngestStats ingestPackage(PackageCoord coord, Path outRoot) throws IOException, InterruptedException {
        Path tarball = downloadPackage(coord);
        Path extracted = extractPackage(tarball);
        Path igOutDir = outRoot.resolve(coord.slug());

        IgMetadata meta = readPackageMetadata(extracted);
        System.out.printf("  → %s%n", meta.citation());

        List<EmittedTopic> emitted = walkAndEmit(extracted, coord, meta, igOutDir);
        writeRegistryShard(emitted, coord, meta, outRoot.getParent().getParent().getParent());
        writeIncludesFile(emitted, coord, meta, igOutDir);

        // Cleanup temp dirs
        deleteTree(extracted);
        Files.deleteIfExists(tarball);

        return summarize(emitted);
    }

    private static IngestStats summarize(List<EmittedTopic> emitted) {
        int sd = (int) emitted.stream().filter(e -> e.resourceType.equals("StructureDefinition")).count();
        int vs = (int) emitted.stream().filter(e -> e.resourceType.equals("ValueSet")).count();
        int cs = (int) emitted.stream().filter(e -> e.resourceType.equals("CodeSystem")).count();
        return new IngestStats(sd, vs, cs);
    }

    // ── Download + extract ───────────────────────────────────────────

    private static Path downloadPackage(PackageCoord coord) throws IOException, InterruptedException {
        String url = REGISTRY_BASE + "/" + coord.packageId() + "/" + coord.version();
        System.out.printf("  downloading %s%n", url);

        Path target = Files.createTempFile("ig-package-" + coord.slug() + "-", ".tgz");

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        HttpResponse<Path> resp = client.send(req,
                HttpResponse.BodyHandlers.ofFile(target,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));

        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " fetching " + url);
        }
        return target;
    }

    private static Path extractPackage(Path tarball) throws IOException {
        Path tempDir = Files.createTempDirectory("ig-extracted-");
        try (InputStream fin = Files.newInputStream(tarball);
             BufferedInputStream bin = new BufferedInputStream(fin);
             GzipCompressorInputStream gz = new GzipCompressorInputStream(bin);
             TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                Path out = tempDir.resolve(e.getName()).normalize();
                if (!out.startsWith(tempDir)) {
                    // Defend against path traversal.
                    throw new IOException("Tar entry escapes target dir: " + e.getName());
                }
                Files.createDirectories(out.getParent());
                Files.copy(tar, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return tempDir;
    }

    // ── Metadata ─────────────────────────────────────────────────────

    /// IG-level metadata extracted from the package's `package.json`.
    record IgMetadata(String title, String version, String canonical, String publisher) {
        String citation() {
            String pub = (publisher == null || publisher.isBlank()) ? "HL7 International" : publisher;
            return "%s. %s, version %s. %s.".formatted(pub, title, version, canonical);
        }
    }

    private static IgMetadata readPackageMetadata(Path extracted) throws IOException {
        Path packageJson = extracted.resolve("package").resolve("package.json");
        if (!Files.exists(packageJson)) {
            throw new IOException("package/package.json not found in extracted tarball");
        }
        JsonNode pkg = JSON.readTree(Files.newBufferedReader(packageJson));
        return new IgMetadata(
                text(pkg, "title", "Untitled IG"),
                text(pkg, "version", "unknown"),
                text(pkg, "canonical", ""),
                text(pkg, "author", text(pkg, "publisher", "HL7 International")));
    }

    // ── Walk + emit ──────────────────────────────────────────────────

    record EmittedTopic(String topicId, String relPath, String title, String resourceType,
                        String resourceId, String canonical, String description) {}

    private static List<EmittedTopic> walkAndEmit(Path extracted, PackageCoord coord,
                                                  IgMetadata meta, Path igOutDir) throws IOException {
        Path pkgRoot = extracted.resolve("package");
        if (!Files.isDirectory(pkgRoot)) {
            throw new IOException("extracted tarball missing package/ root");
        }

        List<EmittedTopic> emitted = new ArrayList<>();
        Files.createDirectories(igOutDir);

        try (Stream<Path> walk = Files.list(pkgRoot)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .forEach(jsonFile -> {
                    try {
                        Optional<EmittedTopic> topic = emitTopicForResource(jsonFile, coord, meta, igOutDir);
                        topic.ifPresent(emitted::add);
                    } catch (IOException e) {
                        System.err.printf("  ! skipping %s: %s%n",
                                pkgRoot.relativize(jsonFile), e.getMessage());
                    }
                });
        }
        return emitted;
    }

    private static Optional<EmittedTopic> emitTopicForResource(Path jsonFile, PackageCoord coord,
                                                               IgMetadata meta, Path igOutDir) throws IOException {
        JsonNode r = JSON.readTree(Files.newBufferedReader(jsonFile));
        String resourceType = text(r, "resourceType", "");
        if (!isSupportedResourceType(resourceType)) {
            return Optional.empty();
        }
        String id = text(r, "id", null);
        if (id == null) return Optional.empty();

        String subDir = subdirForResourceType(resourceType);
        Path subDirPath = igOutDir.resolve(subDir);
        Files.createDirectories(subDirPath);

        String topicId = "ext-standards-" + coord.slug() + "-" + subDir + "-" + safeSlug(id);
        String title = text(r, "title", text(r, "name", id));
        String url = text(r, "url", "");
        String description = text(r, "description", "");

        String fragment = renderFragment(topicId, title, resourceType, id, url,
                description, r, coord, meta, jsonFile.getFileName().toString());

        Path out = subDirPath.resolve(safeSlug(id) + ".adoc");
        Files.writeString(out, fragment);

        String relPath = "topics/ext/standards/" + coord.slug() + "/" + subDir + "/" + safeSlug(id) + ".adoc";
        return Optional.of(new EmittedTopic(topicId, relPath, title, resourceType, id, url, description));
    }

    private static boolean isSupportedResourceType(String resourceType) {
        return "StructureDefinition".equals(resourceType)
                || "ValueSet".equals(resourceType)
                || "CodeSystem".equals(resourceType);
    }

    private static String subdirForResourceType(String resourceType) {
        return switch (resourceType) {
            case "StructureDefinition" -> "profiles";
            case "ValueSet" -> "valuesets";
            case "CodeSystem" -> "codesystems";
            default -> "other";
        };
    }

    // ── Fragment rendering ───────────────────────────────────────────

    private static String renderFragment(String topicId, String title, String resourceType,
                                         String resourceId, String canonical, String description,
                                         JsonNode resource, PackageCoord coord, IgMetadata meta,
                                         String sourceFile) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("// ").append(topicId).append("\n");
        sb.append("// Topic: ").append(escapeComment(title)).append(" (")
          .append(resourceType).append(")\n");
        sb.append("// Type: reference\n");
        sb.append("// Status: review\n");
        sb.append(":topic-id: ").append(topicId).append("\n");
        sb.append(":topic-type: reference\n");
        sb.append(":topic-status: review\n");
        sb.append(":topic-provenance: external\n");
        sb.append(":topic-citation: ").append(meta.citation()).append("\n");
        sb.append(":topic-license: Fair use summary of copyrighted work — not for redistribution.\n");
        sb.append(":topic-keywords: ").append(coord.slug()).append(", ")
          .append(resourceType.toLowerCase()).append(", ").append(safeSlug(resourceId)).append("\n");
        sb.append("\n");
        sb.append("[[").append(topicId).append("]]\n");
        sb.append("= ").append(escapeAsciidocHeader(title)).append("\n\n");

        // Metadata table
        sb.append("[cols=\"1,3\", options=\"header\"]\n");
        sb.append("|===\n");
        sb.append("| Attribute | Value\n\n");
        sb.append("| Resource type | `").append(resourceType).append("`\n");
        sb.append("| Resource id   | `").append(resourceId).append("`\n");
        if (!canonical.isEmpty()) {
            sb.append("| Canonical URL | ").append(canonical).append("\n");
        }
        appendResourceTypeSpecificMetadata(sb, resourceType, resource);
        sb.append("| Source file   | `package/").append(sourceFile).append("`\n");
        sb.append("| IG package    | `").append(coord.packageId()).append("` @ `").append(coord.version()).append("`\n");
        sb.append("|===\n\n");

        // Description
        if (!description.isBlank()) {
            sb.append("== Description\n\n");
            sb.append(stripMarkup(description)).append("\n\n");
        }

        appendResourceTypeSpecificBody(sb, resourceType, resource);

        return sb.toString();
    }

    private static void appendResourceTypeSpecificMetadata(StringBuilder sb, String resourceType, JsonNode r) {
        switch (resourceType) {
            case "StructureDefinition" -> {
                sb.append("| Kind          | `").append(text(r, "kind", "")).append("`\n");
                sb.append("| Type          | `").append(text(r, "type", "")).append("`\n");
                String base = text(r, "baseDefinition", "");
                if (!base.isEmpty()) sb.append("| Base definition | ").append(base).append("\n");
                sb.append("| Derivation    | `").append(text(r, "derivation", "")).append("`\n");
                sb.append("| Abstract      | `").append(text(r, "abstract", "false")).append("`\n");
            }
            case "ValueSet" -> {
                String status = text(r, "status", "");
                if (!status.isEmpty()) sb.append("| Status        | `").append(status).append("`\n");
                String exp = text(r, "experimental", "");
                if (!exp.isEmpty()) sb.append("| Experimental  | `").append(exp).append("`\n");
                String immutable = text(r, "immutable", "");
                if (!immutable.isEmpty()) sb.append("| Immutable     | `").append(immutable).append("`\n");
            }
            case "CodeSystem" -> {
                String content = text(r, "content", "");
                if (!content.isEmpty()) sb.append("| Content       | `").append(content).append("`\n");
                String count = text(r, "count", "");
                if (!count.isEmpty()) sb.append("| Concept count | `").append(count).append("`\n");
                String hierarchy = text(r, "hierarchyMeaning", "");
                if (!hierarchy.isEmpty()) sb.append("| Hierarchy meaning | `").append(hierarchy).append("`\n");
            }
            default -> { /* nothing */ }
        }
    }

    private static void appendResourceTypeSpecificBody(StringBuilder sb, String resourceType, JsonNode r) {
        switch (resourceType) {
            case "StructureDefinition" -> {
                JsonNode diff = r.path("differential").path("element");
                if (diff.isArray() && diff.size() > 0) {
                    sb.append("== Differential elements\n\n");
                    sb.append("[cols=\"2,1,1,3\", options=\"header\"]\n");
                    sb.append("|===\n");
                    sb.append("| Path | Min | Max | Notes\n\n");
                    for (JsonNode el : diff) {
                        String path = text(el, "path", "");
                        String min = text(el, "min", "");
                        String max = text(el, "max", "");
                        String short_ = text(el, "short", "");
                        sb.append("| `").append(path).append("` | ")
                          .append(min).append(" | ").append(max).append(" | ")
                          .append(stripMarkup(short_)).append("\n");
                    }
                    sb.append("|===\n\n");
                }
            }
            case "ValueSet" -> {
                JsonNode include = r.path("compose").path("include");
                if (include.isArray() && include.size() > 0) {
                    sb.append("== Compose include\n\n");
                    sb.append("[cols=\"2,3\", options=\"header\"]\n");
                    sb.append("|===\n");
                    sb.append("| System | Notes\n\n");
                    for (JsonNode inc : include) {
                        String system = text(inc, "system", "");
                        String version = text(inc, "version", "");
                        String filter = inc.has("filter") ? "with filter" : "";
                        String concepts = inc.has("concept") ? inc.path("concept").size() + " concepts" : "";
                        String notes = Stream.of(version.isEmpty() ? "" : "v" + version, filter, concepts)
                                .filter(s -> !s.isEmpty())
                                .reduce((a, b) -> a + ", " + b).orElse("(unspecified)");
                        sb.append("| ").append(system).append(" | ").append(notes).append("\n");
                    }
                    sb.append("|===\n\n");
                }
            }
            case "CodeSystem" -> {
                JsonNode concepts = r.path("concept");
                if (concepts.isArray() && concepts.size() > 0) {
                    int max = Math.min(concepts.size(), 50);
                    sb.append("== Concepts");
                    if (concepts.size() > max) {
                        sb.append(" (first ").append(max).append(" of ").append(concepts.size()).append(")");
                    }
                    sb.append("\n\n");
                    sb.append("[cols=\"1,3\", options=\"header\"]\n");
                    sb.append("|===\n");
                    sb.append("| Code | Display\n\n");
                    for (int i = 0; i < max; i++) {
                        JsonNode c = concepts.get(i);
                        sb.append("| `").append(text(c, "code", "")).append("` | ")
                          .append(stripMarkup(text(c, "display", ""))).append("\n");
                    }
                    sb.append("|===\n\n");
                }
            }
            default -> { /* nothing */ }
        }
    }

    // ── Registry shard ───────────────────────────────────────────────

    /// Write the per-IG registry shard alongside the project's
    /// `topic-registry.yaml`. Output path:
    /// `{registryRoot}/topic-registry/ext-standards-{ig-slug}.yaml`
    private static void writeRegistryShard(List<EmittedTopic> emitted, PackageCoord coord,
                                           IgMetadata meta, Path topicsRoot) throws IOException {
        // topicsRoot is the topics/src/docs/asciidoc/ directory
        Path shardDir = topicsRoot.resolve("topic-registry");
        Files.createDirectories(shardDir);
        Path shardFile = shardDir.resolve("ext-standards-" + coord.slug() + ".yaml");

        StringBuilder sb = new StringBuilder(8192);
        sb.append("# ext-standards-").append(coord.slug()).append(" — auto-generated by IgPublisherIngester\n");
        sb.append("# Source: ").append(coord.packageId()).append("@").append(coord.version()).append("\n");
        sb.append("# Citation: ").append(meta.citation()).append("\n");
        sb.append("\n");
        sb.append("  - id: ext-standards-").append(coord.slug()).append("\n");
        sb.append("    title: \"").append(yamlEscape(meta.title())).append("\"\n");
        sb.append("    description: >\n");
        sb.append("      ").append(yamlEscape(meta.citation())).append("\n");
        sb.append("      ").append(emitted.size()).append(" ingested topics\n");
        sb.append("      (StructureDefinitions, ValueSets, CodeSystems).\n");
        sb.append("      License: Fair use summary of copyrighted work —\n");
        sb.append("      not for redistribution.\n");
        sb.append("    topics:\n");

        // Sort by resourceType then id for stable output
        List<EmittedTopic> sorted = new ArrayList<>(emitted);
        sorted.sort(Comparator.comparing((EmittedTopic e) -> e.resourceType)
                              .thenComparing(e -> e.topicId));
        for (EmittedTopic t : sorted) {
            sb.append("\n");
            sb.append("      - id: ").append(t.topicId).append("\n");
            sb.append("        file: ").append(t.relPath).append("\n");
            sb.append("        title: \"").append(yamlEscape(t.title)).append("\"\n");
            sb.append("        type: reference\n");
            sb.append("        keywords: [").append(coord.slug()).append(", ")
              .append(t.resourceType.toLowerCase()).append(", ").append(safeSlug(t.resourceId)).append("]\n");
            sb.append("        status: review\n");
            sb.append("        provenance: external\n");
            sb.append("        canonical: ").append(t.canonical).append("\n");
            sb.append("        resource-type: ").append(t.resourceType).append("\n");
            sb.append("        dependencies: []\n");
            sb.append("        related: []\n");
            if (!t.description.isBlank()) {
                sb.append("        summary: >\n");
                sb.append("          ").append(yamlEscape(truncate(t.description, 400))).append("\n");
            }
        }

        Files.writeString(shardFile, sb.toString());
        System.out.printf("  wrote registry shard: %s%n", shardFile);
    }

    // ── Per-IG _includes.adoc ────────────────────────────────────────

    /// Write `{igOutDir}/_includes.adoc` — a single AsciiDoc fragment
    /// that an assembly module can include with one directive to pull
    /// in every ingested topic for the IG, organized into Profiles,
    /// Value Sets, and Code Systems sections.
    private static void writeIncludesFile(List<EmittedTopic> emitted, PackageCoord coord,
                                          IgMetadata meta, Path igOutDir) throws IOException {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("// Auto-generated by IgPublisherIngester for ")
          .append(coord).append(".\n");
        sb.append("// Do not hand-edit; re-run the ingester to refresh.\n\n");

        emitSection(sb, emitted, "StructureDefinition", "Profiles");
        emitSection(sb, emitted, "ValueSet", "Value Sets");
        emitSection(sb, emitted, "CodeSystem", "Code Systems");

        Files.writeString(igOutDir.resolve("_includes.adoc"), sb.toString());
    }

    private static void emitSection(StringBuilder sb, List<EmittedTopic> emitted,
                                    String resourceType, String sectionTitle) {
        List<EmittedTopic> filtered = emitted.stream()
                .filter(e -> e.resourceType.equals(resourceType))
                .sorted(Comparator.comparing(e -> e.topicId))
                .toList();
        if (filtered.isEmpty()) return;
        sb.append("== ").append(sectionTitle).append("\n\n");
        String subDir = subdirForResourceType(resourceType);
        for (EmittedTopic t : filtered) {
            sb.append("include::").append(subDir).append("/").append(safeSlug(t.resourceId))
              .append(".adoc[leveloffset=+1]\n\n");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String text(JsonNode n, String field, String dflt) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? dflt : v.asText(dflt);
    }

    private static String safeSlug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String escapeComment(String s) {
        return s.replace("\n", " ");
    }

    private static String escapeAsciidocHeader(String s) {
        return s.replace("\n", " ");
    }

    private static String stripMarkup(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String yamlEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").trim();
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max);
        return s.substring(0, Math.max(cut, max - 20)) + "…";
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equals(flag)) return true;
        return false;
    }

    private static String requireArg(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) return args[i + 1];
        }
        throw new IllegalArgumentException("Missing required argument: " + flag);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: IgPublisherIngester --package <name>@<version> [--package ...] --out <output-dir>

                  --package  FHIR package coordinate, e.g. hl7.fhir.us.core@8.0.0.
                             May be repeated to ingest multiple IGs in one run.
                  --out      Output root, typically the corpus's
                             topics/src/docs/asciidoc/topics/ext/standards/ path.

                Resolves each package against the FHIR registry at
                packages.fhir.org/packages, downloads the .tgz, walks
                StructureDefinitions / ValueSets / CodeSystems, emits one
                AsciiDoc topic fragment per resource under
                {out}/{ig-slug}/{kind}/{resource-id}.adoc, and writes a
                per-IG topic-registry shard under topic-registry/.

                Tracking issue: IKE-Network/ike-issues#549.
                """);
    }
}
