# hl7-ig-corpus-example — Claude Standards

## What this repo is

The first worked example of the IKE *corpus-example* pattern (proposed in
[IKE-Network/ike-issues#538](https://github.com/IKE-Network/ike-issues/issues/538);
design topic `dev-corpus-example-pattern` lives in `ike-lab-documents`).

A multi-module documentation project that:

1. Ingests HL7 implementation guides via the `ingest/` module,
2. Places ingested topic fragments under `topics/ext/standards/{ig-id}/`,
3. Reconstructs each IG as one document in a per-IG assembly module,
4. Surfaces cross-IG overlap and conflict in analysis assembly modules.

## Initial Setup — ALWAYS DO THIS FIRST

Run `mvn validate` before any other work. This unpacks build standards
into `.claude/standards/` for each module. Do not proceed without it.

After validate completes, read and follow the standards in
`.claude/standards/`:

- `MAVEN.md` — Maven 4 build standards
- `IKE-MAVEN.md` — IKE-specific Maven conventions
- `IKE-DOC.md` — Documentation project standards
- `IKE-INGEST.md` — Document ingestion workflow (read whenever ingesting
  an IG; the `ingest/` module follows the External Source Ingestion
  §"Standards" content-handling rules)
- `IKE-TOPIC-DECOMPOSITION.md`, `IKE-TOPIC-REGISTRY.md`,
  `IKE-ASCIIDOC-FRAGMENT.md`, `IKE-ASSEMBLY.md`, `IKE-INDEX.md`
- `JAVA.md` and `IKE-JAVA.md` — read when working in the `ingest/` module

## Module overview

The aggregator POM has `pom` packaging and declares six subprojects.

| Module             | Role                                            | Packaging |
| ------------------ | ----------------------------------------------- | --------- |
| `ingest`           | HL7 IG-publisher bundle normalizer (Java)       | `jar`     |
| `topics`           | Topic library — framing + ingested IG content   | `pom`     |
| `corpus-guide`     | Narrative pattern guide (reader entry point)    | `pom`     |
| `compendium`       | All-topics validation assembly                  | `pom`     |
| `us-core`          | Per-IG assembly (placeholder until ingestion)   | `pom`     |
| `cross-ig-overlap` | Cross-IG analysis assembly (placeholder)        | `pom`     |

Assembly modules inherit from `ike-parent`. The `ingest` module inherits
from `ike-parent` and produces an executable jar.

## Content placement rules

- Framing topics → `topics/src/docs/asciidoc/topics/intro/`
- Analytic method topics → `topics/src/docs/asciidoc/topics/analysis/`
- Ingested IG content → `topics/src/docs/asciidoc/topics/ext/standards/{ig-package-id}/`
  - Every ingested topic carries `:topic-provenance: external`,
    `:topic-citation:`, `:topic-license:` per `IKE-INGEST.md`
  - Content is **fair-use summary** (HL7 standards are copyrighted);
    direct quotation is limited and in quote blocks with attribution

## Ingestion workflow (quick reference)

```bash
# Resolve a bundle (download from build.fhir.org or run IG publisher)
mvn -pl ingest exec:java \
  -Dexec.args="--bundle path/to/us-core-bundle \
               --out topics/src/main/asciidoc/topics/ext/standards/us-core"

# Normalize line breaks (see IKE-INGEST §"Step 2"); run from ike-docs:
mvn exec:java -pl semantic-linebreak -f ../../ike-docs/pom.xml \
  -Dexec.args="topics/src/main/asciidoc/topics/ext/standards/us-core"

# Register topics in topic-registry.yaml (merge the shard the ingester
# emitted) and add includes to the appropriate assembly + compendium.

# Verify
mvn clean verify
```

## Adding a new IG

1. Run the ingester (`mvn -pl ingest exec:java ...`) for the new IG.
2. Add a new per-IG assembly module by cloning `us-core/` →
   `{ig-package-id}/` and updating the parent POM's `<subprojects>`.
3. Register the new topics in `topics/src/docs/asciidoc/topic-registry.yaml`.
4. Add includes to the per-IG assembly and to `compendium`.
5. Build and verify.

## Adding a new analysis assembly

1. Clone `cross-ig-overlap/` as a starting template.
2. Author the analytic content using `xref:` macros over the corpus
   topic-anchors (see `analysis-cross-ig-overlap-method` topic for the
   approach).
3. Add the module to the parent POM's `<subprojects>`.

## What NOT to do

- Don't `sed`/`awk`/regex on POMs (per `feedback_no_sed_on_poms`).
  Use OpenRewrite recipes or the Maven 4 model API.
- Don't include `topics/ext/...` content in `topics/src/docs/asciidoc/index.adoc`
  if doing so violates the external-source assembly-exclusion rule —
  see `IKE-INGEST.md` §"Assembly exclusion rule" for the nuance
  (external topics *can* be cross-referenced via `xref:` but should
  not be `include::`'d into assemblies that publish — for this
  corpus, the per-IG assemblies are themselves the exception, since
  reconstructing an IG is the assembly's purpose).
