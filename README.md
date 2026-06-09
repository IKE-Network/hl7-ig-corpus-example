# hl7-ig-corpus-example

<a href="https://ike.network">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://ike.network/brand/powered-by/powered-by-ike-color-on-dark.svg">
    <img alt="Powered by IKE" height="28" src="https://ike.network/brand/powered-by/powered-by-ike-color-on-light.svg">
  </picture>
</a>

First worked example of the IKE *corpus-example* pattern.

A multi-module documentation project that ingests a corpus of HL7
implementation guides into the IKE topic library and produces both
per-IG reconstruction assemblies and cross-IG analytic outputs.

## Status

Scaffold. The `ingest/` module is a skeleton; topic library carries
the framing topics but no ingested IG content yet. First ingestion
target: US Core.

See [IKE-Network/ike-issues#538][issue-538] for the corpus-example
pattern proposal and [IKE-Network/ike-issues#539][issue-539] for the
scaffold tracking issue.

[issue-538]: https://github.com/IKE-Network/ike-issues/issues/538
[issue-539]: https://github.com/IKE-Network/ike-issues/issues/539

## Modules

| Module             | Role                                                                |
| ------------------ | ------------------------------------------------------------------- |
| `ingest`           | Java — converts HL7 IG-publisher bundles to topic fragments         |
| `topics`           | Topic library — framing + ingested `ext/standards/` content         |
| `corpus-guide`     | Narrative explaining the corpus-example pattern (reader entry)      |
| `compendium`       | All-topics validation assembly                                      |
| `us-core`          | Per-IG reconstruction assembly (placeholder)                        |
| `cross-ig-overlap` | Cross-IG analysis assembly (placeholder)                            |

**Start with `corpus-guide`** if you want to understand the pattern; the
other assemblies are validation or content-specific outputs.

## Relationship to other examples

- `doc-example` — single-document reference; pipeline conformance vehicle.
- `project-example` — single-artifact Java reference.
- **`hl7-ig-corpus-example`** — multi-module corpus ingestion + cross-document analysis.
- (forthcoming) `fda-ifu-corpus-example` — same pattern, FDA IFU sources, DeX extraction.

## Build

```bash
mvn validate                            # unpack build standards
mvn clean verify                        # HTML for all assemblies
mvn clean verify -Dike.pdf.prawn        # HTML + Prawn PDF
mvn clean verify -pl compendium -am     # single assembly with topic deps
```

## License

Apache License, Version 2.0. Ingested IG content is summarized under
fair use (HL7 standards are copyrighted); see each `ext/standards/{ig}/`
topic's `:topic-license:` attribute for the specific handling.
