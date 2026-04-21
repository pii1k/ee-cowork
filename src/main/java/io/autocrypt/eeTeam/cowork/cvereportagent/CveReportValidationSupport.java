package io.autocrypt.eeTeam.cowork.cvereportagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocrypt.eeTeam.cowork.cvereportagent.domain.CveReportRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic input validation that can run without Spring Boot or LLM providers.
 */
public final class CveReportValidationSupport {

    private CveReportValidationSupport() {}

    public record ValidationResult(boolean ok, List<String> checks, List<String> warnings, List<String> errors) {
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("Validation Result\n");
            sb.append("=================\n");
            sb.append("Status: ").append(ok ? "OK" : "FAILED").append("\n\n");

            if (!checks.isEmpty()) {
                sb.append("Checks\n");
                for (String check : checks) {
                    sb.append("- ").append(check).append("\n");
                }
                sb.append("\n");
            }

            if (!warnings.isEmpty()) {
                sb.append("Warnings\n");
                for (String warning : warnings) {
                    sb.append("- ").append(warning).append("\n");
                }
                sb.append("\n");
            }

            if (!errors.isEmpty()) {
                sb.append("Errors\n");
                for (String error : errors) {
                    sb.append("- ").append(error).append("\n");
                }
            }

            return sb.toString().trim();
        }
    }

    public static ValidationResult validateInputs(CveReportRequest request, ObjectMapper objectMapper) throws IOException {
        List<String> checks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (request.productName() == null || request.productName().isBlank()) {
            errors.add("`--product` must not be blank.");
        } else {
            checks.add("Product name provided: " + request.productName());
        }

        Path sbomPath = Paths.get(request.sbomPath()).normalize();
        if (!Files.exists(sbomPath)) {
            errors.add("SBOM file not found: " + sbomPath);
        } else {
            checks.add("SBOM file exists: " + sbomPath);
            validateSbomJson(sbomPath, objectMapper, checks, warnings, errors);
        }

        Path licenseMapPath = Paths.get(request.licenseMapPath()).normalize();
        if (!Files.exists(licenseMapPath)) {
            errors.add("license_map file not found: " + licenseMapPath);
        } else {
            checks.add("license_map file exists: " + licenseMapPath);
            validateLicenseMapJson(licenseMapPath, objectMapper, checks, warnings, errors);
        }

        if (request.buildDirectory() == null || request.buildDirectory().isBlank()) {
            warnings.add("No build directory provided. Binary/build artifact evidence will be skipped.");
        } else {
            Path buildPath = Paths.get(request.buildDirectory()).normalize();
            if (!Files.exists(buildPath) || !Files.isDirectory(buildPath)) {
                warnings.add("Build directory does not exist or is not a directory: " + buildPath);
            } else {
                checks.add("Build directory exists: " + buildPath);
            }
        }

        if (request.analystNotes() == null || request.analystNotes().isBlank()) {
            warnings.add("No analyst notes provided. Only deterministic auto-classification rules will be used.");
        } else {
            checks.add("Analyst notes provided.");
        }

        checks.add("This validation path does not initialize Spring Boot or any external LLM provider.");
        return new ValidationResult(errors.isEmpty(), checks, warnings, errors);
    }

    private static void validateSbomJson(
            Path path,
            ObjectMapper objectMapper,
            List<String> checks,
            List<String> warnings,
            List<String> errors
    ) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(path));
        } catch (Exception e) {
            errors.add("SBOM JSON parse failed: " + e.getMessage());
            return;
        }

        String bomFormat = root.path("bomFormat").asText("");
        if (!"CycloneDX".equalsIgnoreCase(bomFormat)) {
            warnings.add("SBOM bomFormat is not CycloneDX: " + (bomFormat.isBlank() ? "<missing>" : bomFormat));
        } else {
            checks.add("SBOM bomFormat is CycloneDX.");
        }

        if (!root.path("components").isArray()) {
            errors.add("SBOM `components` field is missing or not an array.");
        } else {
            checks.add("SBOM components count: " + root.path("components").size());
        }

        if (!root.path("vulnerabilities").isArray()) {
            warnings.add("SBOM `vulnerabilities` field is missing or not an array. Candidate collection may rely only on supplemental sources.");
        } else {
            checks.add("SBOM vulnerabilities count: " + root.path("vulnerabilities").size());
        }
    }

    private static void validateLicenseMapJson(
            Path path,
            ObjectMapper objectMapper,
            List<String> checks,
            List<String> warnings,
            List<String> errors
    ) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(path));
        } catch (Exception e) {
            errors.add("license_map JSON parse failed: " + e.getMessage());
            return;
        }

        if (!root.path("third_party").isObject()) {
            errors.add("license_map `third_party` field is missing or not an object.");
        } else {
            checks.add("license_map third_party entries: " + root.path("third_party").size());
        }

        if (!root.path("core_libraries").isArray()) {
            warnings.add("license_map `core_libraries` field is missing or not an array.");
        } else {
            checks.add("license_map core_libraries entries: " + root.path("core_libraries").size());
        }
    }
}
