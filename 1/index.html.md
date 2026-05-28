---
date_published: 2026-05-27
date_modified: 2026-05-27
canonical_url: https://ike.network/hl7-ig-corpus-example/index.html
---

# hl7-ig-corpus-example

The first worked example of the IKE **corpus-example** pattern — a multi-module documentation project that ingests a corpus of HL7 implementation guides into the IKE topic library and produces both per-IG reconstruction assemblies and cross-IG analytic outputs.

## [#where-to-start](#where-to-start)Where to Start

| If you want to … | Read … |
| --- | --- |
| Understand the corpus-example **pattern** itself | The [corpus-guide assembly](corpus-guide/)[1] — narrative entry point covering what the pattern is, how ingestion works, and how to extend it. |
| See the **design proposal** for the pattern | [ike-issues#538](https://github.com/IKE-Network/ike-issues/issues/538)[2] and the `dev-corpus-example-pattern` topic in [ike-lab-documents](https://github.com/kec/ike-lab-documents)[3]. |
| Browse **every topic** in the corpus | The [compendium assembly](compendium/)[4] — kitchen-sink validation target. |
| See how a single IG is **reconstructed** | The [us-core assembly](us-core/)[5] (placeholder until US Core ingestion lands). |
| See **cross-IG analysis** output | The [cross-ig-overlap assembly](cross-ig-overlap/)[6] (placeholder until a second IG is ingested). |

## [#relationship-to-the-other-examples](#relationship-to-the-other-examples)Relationship to the other examples

| Example | What it demonstrates |
| --- | --- |
| [doc-example](https://ike.network/doc-example/)[7] | Single-document pipeline reference; renderer conformance vehicle. |
| [project-example](https://ike.network/project-example/)[8] | Single-artifact Java reference. |
| [workspace-reactor-example](https://ike.network/workspace-reactor-example/)[9] | Multi-project workspace reference; `ws:*` orchestration goals. |
| **hl7-ig-corpus-example** (this site) | First corpus-example. Multi-module ingestion of a third-party document corpus with per-source reconstruction and cross-corpus analytic assemblies. |

A future `fda-ifu-corpus-example` will apply the same pattern to FDA manufacturer Instructions for Use, emitting structured Device Extension records with topic-level evidence bindings.

## [#modules](#modules)Modules

| Module | Role |
| --- | --- |
| [ingest](ingest/)[10] | Java skeleton — converts HL7 IG-publisher output bundles into AsciiDoc topic fragments under `topics/ext/standards/{ig-id}/`. First real run: US Core. |
| [topics](topics/)[11] | Topic library following the `IKE-INGEST.md` standard layout. Holds the framing topics (`intro/`), analytic methods (`analysis/`), and the ingested IG content under `ext/standards/`. |
| [corpus-guide](corpus-guide/)[1] | Narrative pattern guide. The reader-facing entry point — what the pattern is, how ingestion works, how cross-IG analysis works, and how to extend the example. |
| [compendium](compendium/)[4] | Kitchen-sink validation assembly — every published topic must appear here. |
| [us-core](us-core/)[5] | Per-IG reconstruction assembly. Placeholder until US Core ingestion lands. |
| [cross-ig-overlap](cross-ig-overlap/)[6] | Cross-IG analytic assembly. Placeholder until at least two IGs are ingested. Analytic method is described in the `analysis-cross-ig-overlap-method` topic. |

## [#build-commands](#build-commands)Build Commands

```
# Unpack build standards into .claude/standards/:
mvn validate

# HTML for all assemblies (default):
mvn clean verify

# HTML + Prawn PDF:
mvn clean verify -Dike.pdf.prawn

# Single assembly:
mvn clean verify -pl corpus-guide -am
```

## [#coordinates](#coordinates)Coordinates

| Coordinate | Value |
| --- | --- |
| Group ID | `network.ike.examples` |
| Artifact | `hl7-ig-corpus-example` |
| Packaging | `pom` (multi-module aggregator) |
| Parent | `network.ike.platform:ike-parent` |

## [#not-published-to-maven-central](#not-published-to-maven-central)Not published to Maven Central

Like its sibling examples, `hl7-ig-corpus-example` is a reference template, not a library. Releases tag GitHub and deploy to the IKE Nexus, but the project is deliberately **not** published to Maven Central — nothing should ever declare a dependency on it. You consume this project by reading it and copying its structure when starting a new corpus ingestion project of your own.

## [#resources](#resources)Resources

| Resource | URL |
| --- | --- |
| Source repository | [https://github.com/IKE-Network/hl7-ig-corpus-example](https://github.com/IKE-Network/hl7-ig-corpus-example)[12] |
| Pattern design issue | [https://github.com/IKE-Network/ike-issues/issues/538](https://github.com/IKE-Network/ike-issues/issues/538)[2] |
| Scaffold tracking issue | [https://github.com/IKE-Network/ike-issues/issues/539](https://github.com/IKE-Network/ike-issues/issues/539)[13] |
| Pattern design note | `dev-corpus-example-pattern` in [ike-lab-documents](https://github.com/kec/ike-lab-documents)[3] |
| Cross-project issue tracker | [https://github.com/IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues)[14] |
| IKE Network landing page | [https://ike.network/](https://ike.network/)[15] |
