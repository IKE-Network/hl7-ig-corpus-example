package network.ike.examples.hl7ig.ingest;

import java.nio.file.Path;

/// Entry point for the HL7 IG-publisher bundle ingester.
///
/// Skeleton only. The first real implementation will land alongside
/// the US Core ingestion run (see ike-issues#539 follow-ups).
///
/// Expected invocation:
/// ```
/// mvn -pl ingest exec:java \
///   -Dexec.args="--bundle path/to/ig-bundle \
///                --out ../topics/src/main/asciidoc/topics/ext/standards/us-core"
/// ```
///
/// Behavior contract (to be implemented):
///
///   1. Resolve the bundle directory.
///   2. Read `version.info` and `package.tgz` metadata to derive
///      `:topic-citation:` and `:topic-license:` attribute values.
///   3. Walk the narrative HTML pages and the FHIR package's
///      StructureDefinitions / ValueSets / CodeSystems.
///   4. Emit one `.adoc` fragment per logical section, each carrying
///      the `:topic-provenance: external` attribute and the citation /
///      license attributes derived in step 2.
///   5. Place fragments under the output directory.
///   6. Write a `topic-registry-shard.yaml` enumerating the emitted
///      topics so the corpus registry can merge it in.
public final class IgPublisherIngester {

    private IgPublisherIngester() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            return;
        }

        Path bundle = requireArg(args, "--bundle");
        Path outDir = requireArg(args, "--out");

        // TODO(ike-issues#539): implement IG-publisher bundle walk.
        // See javadoc above for the behavior contract.
        throw new UnsupportedOperationException(
                "IgPublisherIngester is a skeleton. Bundle=%s, out=%s. See ike-issues#539."
                        .formatted(bundle, outDir));
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (a.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static Path requireArg(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return Path.of(args[i + 1]);
            }
        }
        throw new IllegalArgumentException("Missing required argument: " + flag);
    }

    private static void printUsage() {
        System.out.println("""
                Usage: IgPublisherIngester --bundle <ig-bundle-dir> --out <output-dir>

                  --bundle  Path to an HL7 IG-publisher output bundle.
                  --out     Path to topics/src/main/asciidoc/topics/ext/standards/{ig-id}/.

                Skeleton only. See ike-issues#539 for status.
                """);
    }
}
