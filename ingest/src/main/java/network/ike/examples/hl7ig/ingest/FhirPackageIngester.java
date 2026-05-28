package network.ike.examples.hl7ig.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import network.ike.docs.ingest.IncludesFile;
import network.ike.docs.ingest.IncludesFileWriter;
import network.ike.docs.ingest.IngestUtil;
import network.ike.docs.ingest.ProvenanceAttributes;
import network.ike.docs.ingest.RegistryEntry;
import network.ike.docs.ingest.RegistryShard;
import network.ike.docs.ingest.RegistryShardWriter;
import network.ike.docs.ingest.TarballDownloader;
import network.ike.docs.ingest.TopicFragment;
import network.ike.docs.ingest.TopicFragmentWriter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/// FHIR NPM package ingester.
///
/// Downloads FHIR packages from the FHIR package registry, walks each
/// package's conformance resources (StructureDefinitions, ValueSets,
/// CodeSystems), and emits AsciiDoc topic fragments under the corpus's
/// `topics/ext/standards/{ig-slug}/` tree along with a per-IG topic-
/// registry shard and an `_includes.adoc` manifest.
///
/// Source-specific behavior — FHIR registry URL resolution, package
/// metadata extraction, per-resource-type metadata and body rendering —
/// lives in this class. Cross-cutting infrastructure (tarball
/// download/extract, fragment / registry / includes writing,
/// provenance attribute construction, helpers) comes from
/// `network.ike.docs:ike-doc-ingest`.
///
/// Invocation:
/// ```
/// mvn -pl ingest exec:java -Dexec.args="\
///   --package hl7.fhir.us.core@8.0.1 \
///   --package hl7.fhir.us.mcode@4.0.0 \
///   --package hl7.fhir.us.davinci-deqm@5.0.0 \
///   --out ../topics/src/docs/asciidoc/topics/ext/standards"
/// ```
///
/// Tracking: IKE-Network/ike-issues#549 (ingestion), #550 (library).
public final class FhirPackageIngester {

    /// FHIR package registry. `{base}/{packageId}/{version}` returns the package tarball directly.
    private static final String REGISTRY_BASE = "https://packages.fhir.org";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TarballDownloader downloader = new TarballDownloader();
    private final TopicFragmentWriter fragmentWriter = new TopicFragmentWriter();
    private final RegistryShardWriter shardWriter = new RegistryShardWriter();
    private final IncludesFileWriter includesWriter = new IncludesFileWriter();

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

        FhirPackageIngester ingester = new FhirPackageIngester();
        for (PackageCoord coord : packages) {
            System.out.printf("%n=== Ingesting %s ===%n", coord);
            try {
                IngestStats stats = ingester.ingestPackage(coord, outRoot);
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
    record PackageCoord(String packageId, String version) {

        /// Short slug for output paths, derived from the package id.
        String slug() {
            String last = packageId.substring(packageId.lastIndexOf('.') + 1);
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

    /// IG-level metadata extracted from the package's `package.json`.
    record IgMetadata(String title, String version, String canonical, String publisher) {
        String citation() {
            String pub = (publisher == null || publisher.isBlank()) ? "HL7 International" : publisher;
            return "%s. %s, version %s. %s.".formatted(pub, title, version, canonical);
        }
    }

    /// One emitted topic with everything needed to write the per-IG
    /// registry shard and the per-IG _includes.adoc.
    record EmittedTopic(String topicId, String relPath, String title, String resourceType,
                        String resourceId, String canonical, String description) {}

    /// Ingest one IG package: download, extract, walk, emit.
    IngestStats ingestPackage(PackageCoord coord, Path outRoot) throws IOException, InterruptedException {
        URI url = URI.create(REGISTRY_BASE + "/" + coord.packageId() + "/" + coord.version());
        System.out.printf("  downloading %s%n", url);

        Path extracted = downloader.downloadAndExtract(url);
        try {
            IgMetadata meta = readPackageMetadata(extracted);
            System.out.printf("  → %s%n", meta.citation());

            Path igOutDir = outRoot.resolve(coord.slug());
            Files.createDirectories(igOutDir);

            ProvenanceAttributes provenance =
                    ProvenanceAttributes.externalFairUse(meta.citation());

            List<EmittedTopic> emitted = walkAndEmit(extracted, coord, provenance, igOutDir);
            writeRegistryShard(emitted, coord, meta, outRoot);
            writeIncludes(emitted, igOutDir);

            return summarize(emitted);
        } finally {
            deleteTree(extracted);
        }
    }

    private static IngestStats summarize(List<EmittedTopic> emitted) {
        int sd = (int) emitted.stream().filter(e -> e.resourceType.equals("StructureDefinition")).count();
        int vs = (int) emitted.stream().filter(e -> e.resourceType.equals("ValueSet")).count();
        int cs = (int) emitted.stream().filter(e -> e.resourceType.equals("CodeSystem")).count();
        return new IngestStats(sd, vs, cs);
    }

    // ── Package metadata ─────────────────────────────────────────────

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

    private List<EmittedTopic> walkAndEmit(Path extracted, PackageCoord coord,
                                           ProvenanceAttributes provenance, Path igOutDir) throws IOException {
        Path pkgRoot = extracted.resolve("package");
        if (!Files.isDirectory(pkgRoot)) {
            throw new IOException("extracted tarball missing package/ root");
        }

        List<EmittedTopic> emitted = new ArrayList<>();
        try (Stream<Path> walk = Files.list(pkgRoot)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .forEach(jsonFile -> {
                    try {
                        Optional<EmittedTopic> topic =
                                emitTopicForResource(jsonFile, coord, provenance, igOutDir);
                        topic.ifPresent(emitted::add);
                    } catch (IOException e) {
                        System.err.printf("  ! skipping %s: %s%n",
                                pkgRoot.relativize(jsonFile), e.getMessage());
                    }
                });
        }
        return emitted;
    }

    private Optional<EmittedTopic> emitTopicForResource(Path jsonFile, PackageCoord coord,
                                                        ProvenanceAttributes provenance,
                                                        Path igOutDir) throws IOException {
        JsonNode r = JSON.readTree(Files.newBufferedReader(jsonFile));
        String resourceType = text(r, "resourceType", "");
        if (!isSupportedResourceType(resourceType)) {
            return Optional.empty();
        }
        String id = text(r, "id", null);
        if (id == null) return Optional.empty();

        String subDir = subdirForResourceType(resourceType);
        String resourceSlug = IngestUtil.safeSlug(id);
        String topicId = "ext-standards-" + coord.slug() + "-" + subDir + "-" + resourceSlug;
        String title = text(r, "title", text(r, "name", id));
        String canonical = text(r, "url", "");
        String description = text(r, "description", "");

        TopicFragment fragment = new TopicFragment(
                topicId,
                title,
                "reference",
                "review",
                List.of(coord.slug(), resourceType.toLowerCase(), resourceSlug),
                provenance,
                renderBody(resourceType, id, canonical, description, r, coord, jsonFile.getFileName().toString()));

        Path target = igOutDir.resolve(subDir).resolve(resourceSlug + ".adoc");
        fragmentWriter.write(target, fragment);

        String relPath = "topics/ext/standards/" + coord.slug() + "/" + subDir + "/" + resourceSlug + ".adoc";
        return Optional.of(new EmittedTopic(topicId, relPath, title, resourceType, id, canonical, description));
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

    // ── Body rendering ───────────────────────────────────────────────

    private String renderBody(String resourceType, String resourceId, String canonical,
                              String description, JsonNode resource, PackageCoord coord,
                              String sourceFile) {
        StringBuilder sb = new StringBuilder(2048);
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

        if (!description.isBlank()) {
            sb.append("== Description\n\n");
            sb.append(IngestUtil.stripMarkup(description)).append("\n\n");
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
            default -> { }
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
                        sb.append("| `").append(text(el, "path", "")).append("` | ")
                          .append(text(el, "min", "")).append(" | ").append(text(el, "max", "")).append(" | ")
                          .append(IngestUtil.stripMarkup(text(el, "short", ""))).append("\n");
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
                          .append(IngestUtil.stripMarkup(text(c, "display", ""))).append("\n");
                    }
                    sb.append("|===\n\n");
                }
            }
            default -> { }
        }
    }

    // ── Registry shard ───────────────────────────────────────────────

    private void writeRegistryShard(List<EmittedTopic> emitted, PackageCoord coord,
                                    IgMetadata meta, Path outRoot) throws IOException {
        // outRoot is .../topics/src/docs/asciidoc/topics/ext/standards
        // The registry shard goes to .../topics/src/docs/asciidoc/topic-registry/
        Path topicsRoot = outRoot.getParent().getParent().getParent();
        Path shardDir = topicsRoot.resolve("topic-registry");
        Path shardFile = shardDir.resolve("ext-standards-" + coord.slug() + ".yaml");

        List<EmittedTopic> sorted = new ArrayList<>(emitted);
        sorted.sort(
                Comparator.comparing((EmittedTopic e) -> e.resourceType)
                        .thenComparing(e -> e.topicId));

        List<RegistryEntry> entries = new ArrayList<>(sorted.size());
        for (EmittedTopic t : sorted) {
            entries.add(new RegistryEntry(
                    t.topicId,
                    t.relPath,
                    t.title,
                    "reference",
                    List.of(coord.slug(), t.resourceType.toLowerCase(), IngestUtil.safeSlug(t.resourceId)),
                    "review",
                    "external",
                    t.canonical,
                    t.resourceType,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    t.description));
        }

        String description = """
                %s
                %d ingested topics
                (StructureDefinitions, ValueSets, CodeSystems).
                License: Fair use summary of copyrighted work — not for redistribution."""
                .formatted(meta.citation(), emitted.size());

        RegistryShard shard = new RegistryShard(
                "ext-standards-" + coord.slug(),
                meta.title(),
                description,
                entries);

        shardWriter.write(shardFile, shard);
        System.out.printf("  wrote registry shard: %s%n", shardFile);
    }

    // ── Per-IG _includes.adoc ────────────────────────────────────────

    private void writeIncludes(List<EmittedTopic> emitted, Path igOutDir) throws IOException {
        IncludesFile manifest = new IncludesFile(
                "// Auto-generated by FhirPackageIngester.\n" +
                "// Do not hand-edit; re-run the ingester to refresh.",
                List.of(
                        section(emitted, "StructureDefinition", "Profiles"),
                        section(emitted, "ValueSet", "Value Sets"),
                        section(emitted, "CodeSystem", "Code Systems")));
        includesWriter.write(igOutDir.resolve("_includes.adoc"), manifest);
    }

    private static IncludesFile.IncludesSection section(List<EmittedTopic> emitted,
                                                        String resourceType, String title) {
        String subDir = subdirForResourceType(resourceType);
        List<String> paths = emitted.stream()
                .filter(e -> e.resourceType.equals(resourceType))
                .sorted(Comparator.comparing(e -> e.topicId))
                .map(e -> subDir + "/" + IngestUtil.safeSlug(e.resourceId) + ".adoc")
                .toList();
        return new IncludesFile.IncludesSection(title, paths);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String text(JsonNode n, String field, String dflt) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? dflt : v.asText(dflt);
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
                Usage: FhirPackageIngester --package <name>@<version> [--package ...] --out <output-dir>

                  --package  FHIR package coordinate, e.g. hl7.fhir.us.core@8.0.1.
                             May be repeated to ingest multiple IGs in one run.
                  --out      Output root, typically the corpus's
                             topics/src/docs/asciidoc/topics/ext/standards/ path.

                Downloads each FHIR NPM package from packages.fhir.org,
                walks StructureDefinitions / ValueSets / CodeSystems,
                emits AsciiDoc fragments + per-IG topic-registry shard +
                _includes.adoc manifest. Cross-cutting infrastructure
                (tarball download, fragment / registry / includes writing)
                comes from network.ike.docs:ike-doc-ingest.

                Tracking issues: IKE-Network/ike-issues#549, #550.
                """);
    }
}
