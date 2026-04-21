package io.autocrypt.eeTeam.cowork.cvereportagent.domain;

/**
 * Request DTO for generating a CVE applicability report from SBOM inputs.
 */
public record CveReportRequest(
        String sbomPath,
        String productName,
        String productVersion,
        String buildDirectory,
        String licenseMapPath,
        String outputFormat,
        String analystNotes
) {}
