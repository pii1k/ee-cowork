package io.autocrypt.eeTeam.cowork.cvereportagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocrypt.eeTeam.cowork.cvereportagent.domain.CveReportRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standalone entry point for deterministic cvereport stages.
 */
public final class CveReportDeterministicMain {

    private static final String STAGE_GUIDE = """
            Stages
            1. Inventory build
               - Extract components from the SBOM
               - Classify using `license_map`
               - Scan build artifacts
               - Generate `presentInProduct` and evidence
            2. CVE candidate collection
               - SBOM vulnerabilities
               - Merge supplemental grype results
               - Accumulate `matchedBy`
               - Match by `bom-ref` / `purl` / `name@version`
            3. Applicability assessment
               - Not performed by this standalone deterministic runner
               - Performed in the full agent flow
            4. Report generation
               - Not performed by this standalone deterministic runner
               - Performed in the full agent flow
            """;

    private CveReportDeterministicMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> parsed = parseArgs(args);

        if (parsed.containsKey("list-stages")) {
            printStages();
            return;
        }

        if (parsed.containsKey("help")
                || !parsed.containsKey("sbom")
                || !parsed.containsKey("product")
                || !parsed.containsKey("until-stage")) {
            printUsage();
            return;
        }

        int untilStage = parseUntilStage(parsed.get("until-stage"));

        CveReportRequest request = new CveReportRequest(
                parsed.get("sbom"),
                parsed.get("product"),
                parsed.getOrDefault("version", "unknown"),
                parsed.getOrDefault("build-dir", ""),
                parsed.getOrDefault("license-map", "sbom/license_map.json"),
                parsed.getOrDefault("format", "all"),
                parsed.getOrDefault("notes", "")
        );

        ObjectMapper objectMapper = new ObjectMapper();
        CveReportDeterministicSupport.Stage12Result result = CveReportDeterministicSupport.run(request, objectMapper, untilStage);
        System.out.println(result.render());
        if (!result.ok()) {
            System.exit(2);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-h".equals(arg) || "--help".equals(arg)) {
                values.put("help", "true");
                continue;
            }
            if ("--list-stages".equals(arg)) {
                values.put("list-stages", "true");
                continue;
            }
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
            String key = arg.substring(2);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for argument: " + arg);
            }
            values.put(key, args[++i]);
        }
        return values;
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java ... CveReportDeterministicMain --sbom <path> --product <name> --until-stage <1|2> [options]

                """ + STAGE_GUIDE + """

                Options:
                  --sbom <path>
                    Path to CycloneDX SBOM JSON (required)
                    Example:
                      --sbom /workdir/workspace/securityplatform/sbom_mk2/output/acv2x_ee_sbom.json

                  --product <name>
                    Product name (required)
                    Example:
                      --product ACV2X-EE

                  --until-stage <1|2>
                    Required. Stop after stage 1 or stage 2.
                    Example:
                      --until-stage 1

                  --version <value>
                    Product version used in workspace slug/output path
                    Example:
                      --version 5.3.49

                  --build-dir <path>
                    Optional build directory for artifact evidence scanning
                    Example:
                      --build-dir /workdir/workspace/securityplatform/build/x86-64/etsi102941/Debug

                  --license-map <path>
                    Path to license_map.json
                    Example:
                      --license-map /workdir/workspace/securityplatform/sbom_mk2/license_map.json

                  --format <value>
                    Output format hint. Currently informational only.
                    Example:
                      --format all

                  --notes <text>
                    Analyst notes. Accepted for compatibility, not used by stage 1/2 logic.
                    Example:
                      --notes patched:CVE-2026-28389

                  --list-stages
                    Show stage list and supported deterministic scope, then exit

                  --help
                    Show this message

                Examples:
                  1) Stage list only
                     ./mvnw -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \\
                       -Dexec.mainClass=io.autocrypt.eeTeam.cowork.cvereportagent.CveReportDeterministicMain \\
                       -Dexec.args="--list-stages"

                  2) Stage 1 only
                     ./mvnw -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \\
                       -Dexec.mainClass=io.autocrypt.eeTeam.cowork.cvereportagent.CveReportDeterministicMain \\
                       -Dexec.args="--sbom /workdir/workspace/securityplatform/sbom_mk2/output/acv2x_ee_sbom.json --product ACV2X-EE --license-map /workdir/workspace/securityplatform/sbom_mk2/license_map.json --build-dir /workdir/workspace/securityplatform/build/x86-64/etsi102941/Debug --until-stage 1"

                  3) Stage 1 and 2
                     ./mvnw -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \\
                       -Dexec.mainClass=io.autocrypt.eeTeam.cowork.cvereportagent.CveReportDeterministicMain \\
                       -Dexec.args="--sbom /workdir/workspace/securityplatform/sbom_mk2/output/acv2x_ee_sbom.json --product ACV2X-EE --license-map /workdir/workspace/securityplatform/sbom_mk2/license_map.json --build-dir /workdir/workspace/securityplatform/build/x86-64/etsi102941/Debug --until-stage 2"
                """);
    }

    private static void printStages() {
        System.out.println(STAGE_GUIDE);
        System.out.println("Standalone deterministic runner support");
        System.out.println("- `CveReportDeterministicMain` supports stage 1 and stage 2 only");
        System.out.println("- `--until-stage 1` generates `inventory.json` only");
        System.out.println("- `--until-stage 2` generates `inventory.json` and `cve_candidates.json`");
        System.out.println("- Stage 3 and stage 4 require the full agent path");
    }

    private static int parseUntilStage(String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > 2) {
                throw new IllegalArgumentException("`--until-stage` must be 1 or 2 for this standalone runner.");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("`--until-stage` must be numeric: 1 or 2.");
        }
    }
}
