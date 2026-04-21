package io.autocrypt.eeTeam.cowork.cvereportagent.domain;

/**
 * Result DTO for the CVE report agent.
 */
public record CveReportResult(
        String markdownReport,
        String markdownReportPath,
        String jsonReportPath,
        String csvReportPath,
        String summary,
        String status
) {}
