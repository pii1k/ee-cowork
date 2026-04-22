package io.autocrypt.eeTeam.cowork.cvereportagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live external advisory lookup support for deterministic Stage 2 candidate enrichment.
 * This currently uses the OSV API because it supports package/version queries directly.
 */
public final class CveReportExternalAdvisorySupport {

    private static final URI OSV_QUERY_URI = URI.create("https://api.osv.dev/v1/query");
    private static final String OSV_SOURCE = "osv:https://api.osv.dev/v1/query";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private CveReportExternalAdvisorySupport() {}

    public record LookupTarget(
            String componentName,
            String version,
            String purl,
            String repoUrl
    ) {}

    public record ExternalAdvisory(
            String cveId,
            String componentName,
            String componentVersion,
            List<String> matchedBy,
            String severity,
            String cvss,
            String description,
            List<String> fixedVersions,
            List<String> referenceUrls,
            String matchConfidence
    ) {}

    public record LookupResult(
            List<ExternalAdvisory> advisories,
            List<String> lookupSources,
            List<String> warnings
    ) {}

    public static LookupResult lookupOsvAdvisories(
            Map<String, JsonNode> thirdPartyByName,
            ObjectMapper objectMapper
    ) {
        List<LookupTarget> targets = buildLookupTargets(thirdPartyByName);
        if (targets.isEmpty()) {
            return new LookupResult(
                    List.of(),
                    List.of(),
                    List.of("No license_map third-party entries were available for live OSV lookup.")
            );
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<ExternalAdvisory> advisories = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean anyQuerySucceeded = false;

        for (LookupTarget target : targets) {
            try {
                List<ExternalAdvisory> purlAdvisories = queryOsvByPurl(client, objectMapper, target);
                advisories.addAll(purlAdvisories);
                if (purlAdvisories.isEmpty() && shouldUseGitFallback(target)) {
                    advisories.addAll(queryOsvByGit(client, objectMapper, target));
                }
                anyQuerySucceeded = true;
            } catch (Exception e) {
                warnings.add("Live OSV lookup failed for " + target.componentName() + "@"
                        + target.version() + ": " + sanitizeMessage(e.getMessage()));
            }
        }

        if (!anyQuerySucceeded) {
            warnings.add("Live OSV advisory lookup did not succeed. Stage 2 may rely on SBOM, supplemental grype, and local advisory files only.");
        }

        return new LookupResult(
                List.copyOf(advisories),
                anyQuerySucceeded ? List.of(OSV_SOURCE) : List.of(),
                List.copyOf(warnings)
        );
    }

    private static List<LookupTarget> buildLookupTargets(Map<String, JsonNode> thirdPartyByName) {
        Map<String, LookupTarget> deduped = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : thirdPartyByName.entrySet()) {
            String name = entry.getKey();
            JsonNode metadata = entry.getValue();
            String version = textOrDefault(metadata, "version", "");
            String purl = textOrDefault(metadata, "purl", "");
            if (version.isBlank() && purl.isBlank()) {
                continue;
            }
            String key = !purl.isBlank() ? stripPackageIdSuffix(purl) : name + "@" + version;
            deduped.putIfAbsent(key, new LookupTarget(name, version, purl, githubRepoUrlFromPurl(purl)));
        }
        return List.copyOf(deduped.values());
    }

    private static List<ExternalAdvisory> queryOsvByPurl(
            HttpClient client,
            ObjectMapper objectMapper,
            LookupTarget target
    ) throws IOException, InterruptedException {
        if (target.purl().isBlank()) {
            return List.of();
        }
        return queryOsv(client, objectMapper, buildPurlPayload(objectMapper, target), target, "osv");
    }

    private static List<ExternalAdvisory> queryOsvByGit(
            HttpClient client,
            ObjectMapper objectMapper,
            LookupTarget target
    ) throws IOException, InterruptedException {
        if (target.repoUrl().isBlank() || target.version().isBlank()) {
            return List.of();
        }
        return queryOsv(client, objectMapper, buildGitPayload(objectMapper, target), target, "osv_git");
    }

    private static List<ExternalAdvisory> queryOsv(
            HttpClient client,
            ObjectMapper objectMapper,
            ObjectNode basePayload,
            LookupTarget target,
            String matchedBy
    ) throws IOException, InterruptedException {
        List<ExternalAdvisory> advisories = new ArrayList<>();
        String pageToken = "";

        do {
            ObjectNode payload = basePayload.deepCopy();
            if (!pageToken.isBlank()) {
                payload.put("page_token", pageToken);
            }

            HttpRequest request = HttpRequest.newBuilder(OSV_QUERY_URI)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("OSV API returned HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            for (JsonNode vulnNode : iterable(root.path("vulns"))) {
                if (isWithdrawn(vulnNode)) {
                    continue;
                }
                advisories.add(toExternalAdvisory(vulnNode, target, matchedBy));
            }
            pageToken = textOrDefault(root, "next_page_token", "");
        } while (!pageToken.isBlank());

        return advisories;
    }

    private static ObjectNode buildPurlPayload(ObjectMapper objectMapper, LookupTarget target) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putObject("package").put("purl", target.purl());
        return payload;
    }

    private static ObjectNode buildGitPayload(ObjectMapper objectMapper, LookupTarget target) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode pkg = payload.putObject("package");
        pkg.put("ecosystem", "GIT");
        pkg.put("name", target.repoUrl());
        payload.put("version", target.version());
        return payload;
    }

    private static ExternalAdvisory toExternalAdvisory(JsonNode vulnNode, LookupTarget target, String matchedBy) {
        return new ExternalAdvisory(
                textOrDefault(vulnNode, "id", ""),
                target.componentName(),
                target.version(),
                List.of(matchedBy),
                extractSeverity(vulnNode),
                extractCvss(vulnNode),
                firstNonBlank(
                        textOrDefault(vulnNode, "summary", ""),
                        textOrDefault(vulnNode, "details", "")
                ),
                collectFixedVersions(vulnNode),
                collectReferenceUrls(vulnNode),
                !target.purl().isBlank() ? "high" : "medium"
        );
    }

    private static boolean shouldUseGitFallback(LookupTarget target) {
        return !target.repoUrl().isBlank()
                && !target.version().isBlank()
                && target.purl() != null
                && target.purl().startsWith("pkg:github/");
    }

    private static String extractSeverity(JsonNode vulnNode) {
        for (JsonNode severityNode : iterable(vulnNode.path("severity"))) {
            String type = textOrDefault(severityNode, "type", "");
            String score = textOrDefault(severityNode, "score", "");
            if (!type.isBlank()) {
                return type.toUpperCase(Locale.ROOT);
            }
            if (!score.isBlank()) {
                return score;
            }
        }
        for (JsonNode affectedNode : iterable(vulnNode.path("affected"))) {
            String severity = textOrDefault(affectedNode.path("ecosystem_specific"), "severity", "");
            if (!severity.isBlank()) {
                return severity;
            }
            severity = textOrDefault(affectedNode.path("database_specific"), "severity", "");
            if (!severity.isBlank()) {
                return severity;
            }
        }
        return "UNKNOWN";
    }

    private static String extractCvss(JsonNode vulnNode) {
        for (JsonNode severityNode : iterable(vulnNode.path("severity"))) {
            String score = textOrDefault(severityNode, "score", "");
            if (!score.isBlank()) {
                return score;
            }
        }
        return "";
    }

    private static List<String> collectFixedVersions(JsonNode vulnNode) {
        Set<String> fixedVersions = new LinkedHashSet<>();
        for (JsonNode affectedNode : iterable(vulnNode.path("affected"))) {
            for (JsonNode rangeNode : iterable(affectedNode.path("ranges"))) {
                for (JsonNode eventNode : iterable(rangeNode.path("events"))) {
                    String fixed = textOrDefault(eventNode, "fixed", "");
                    if (!fixed.isBlank()) {
                        fixedVersions.add(fixed);
                    }
                }
            }
            String fixed = textOrDefault(affectedNode.path("database_specific"), "fixed_version", "");
            if (!fixed.isBlank()) {
                fixedVersions.add(fixed);
            }
        }
        return List.copyOf(fixedVersions);
    }

    private static List<String> collectReferenceUrls(JsonNode vulnNode) {
        Set<String> refs = new LinkedHashSet<>();
        for (JsonNode referenceNode : iterable(vulnNode.path("references"))) {
            String url = textOrDefault(referenceNode, "url", "");
            if (!url.isBlank()) {
                refs.add(url);
            }
        }
        return List.copyOf(refs);
    }

    private static boolean isWithdrawn(JsonNode vulnNode) {
        return !textOrDefault(vulnNode, "withdrawn", "").isBlank();
    }

    private static List<JsonNode> iterable(JsonNode node) {
        List<JsonNode> items = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return items;
        }
        node.forEach(items::add);
        return items;
    }

    private static String textOrDefault(JsonNode node, String fieldName, String defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        JsonNode child = node.get(fieldName);
        if (child == null || child.isNull()) {
            return defaultValue;
        }
        String text = child.asText("");
        return text.isBlank() ? defaultValue : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String stripPackageIdSuffix(String ref) {
        int idx = ref.indexOf("?package-id=");
        return idx >= 0 ? ref.substring(0, idx) : ref;
    }

    private static String githubRepoUrlFromPurl(String purl) {
        if (purl == null || purl.isBlank() || !purl.startsWith("pkg:github/")) {
            return "";
        }
        String cleaned = stripPackageIdSuffix(purl);
        int at = cleaned.lastIndexOf('@');
        String packagePart = at >= 0 ? cleaned.substring(0, at) : cleaned;
        String repoPath = packagePart.substring("pkg:github/".length());
        if (repoPath.isBlank() || !repoPath.contains("/")) {
            return "";
        }
        return "https://github.com/" + repoPath + ".git";
    }

    private static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.replace('\n', ' ').trim();
    }
}
