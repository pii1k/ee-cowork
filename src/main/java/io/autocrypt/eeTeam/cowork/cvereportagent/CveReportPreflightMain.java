package io.autocrypt.eeTeam.cowork.cvereportagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocrypt.eeTeam.cowork.cvereportagent.domain.CveReportRequest;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standalone entry point for deterministic preflight checks without Spring Boot.
 */
public final class CveReportPreflightMain {

    private CveReportPreflightMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> parsed = parseArgs(args);

        if (parsed.containsKey("help") || !parsed.containsKey("sbom") || !parsed.containsKey("product")) {
            printUsage();
            return;
        }

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
        CveReportPreflightSupport.PreflightResult result =
                CveReportPreflightSupport.runPreflightChecks(request, objectMapper);

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
                  java ... CveReportPreflightMain --sbom <path> --product <name> [options]

                Options:
                  --sbom <path>          Path to CycloneDX SBOM JSON (required)
                  --product <name>       Product name (required)
                  --version <value>      Product version
                  --build-dir <path>     Optional build directory
                  --license-map <path>   Path to license_map.json
                  --format <value>       Output format hint
                  --notes <text>         Analyst notes
                  --help                 Show this message
                """);
    }
}
