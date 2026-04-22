package io.autocrypt.eeTeam.cowork.cvereportagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocrypt.eeTeam.cowork.cvereportagent.domain.CveReportRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Standalone deterministic implementation for cvereport Stage 1 and Stage 2.
 * This path intentionally avoids Spring Boot and any LLM provider initialization.
 */
public final class CveReportDeterministicSupport {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String AGENT_NAME = "cvereportagent";
    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");

    private CveReportDeterministicSupport() {}

    public record ObservedComponent(
            String componentName,
            String version,
            String purl,
            String sourceType,
            String confidence,
            boolean presentInProduct,
            List<String> evidence
    ) {}

    public record CveCandidate(
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

    private record LicenseMapContext(
            Set<String> thirdPartyNames,
            Set<String> coreLibraryNames,
            Set<String> excludedForToolchain,
            Map<String, JsonNode> thirdPartyByName
    ) {}

    private record BuildEvidenceContext(
            String buildDirectory,
            List<String> indexedPaths
    ) {}

    private record KnownComponent(
            String componentName,
            String version,
            String purl
    ) {}

    private record CandidateMatch(
            KnownComponent component,
            String confidence
    ) {}

    private record CandidateCollectionResult(
            List<CveCandidate> candidates,
            List<String> lookupSources,
            List<String> warnings
    ) {}

    private record ComponentClassification(
            String sourceType,
            String confidence,
            boolean presentInProduct,
            List<String> evidence
    ) {}

    public record Stage12Result(
            boolean ok,
            String workspaceId,
            String inventoryPath,
            String candidatePath,
            int executedUntilStage,
            int observedComponentCount,
            int candidateCount,
            List<String> checks,
            List<String> warnings,
            List<String> errors
    ) {
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("Deterministic Stage 1+2 Result\n");
            sb.append("==============================\n");
            sb.append("Status: ").append(ok ? "OK" : "FAILED").append("\n\n");
            sb.append("Execution Summary\n");
            sb.append("- Executed through stage: ").append(executedUntilStage).append("\n");
            sb.append("- Stage 1 (Inventory): ").append(executedUntilStage >= 1 ? "done" : "skipped").append("\n");
            sb.append("- Stage 2 (CVE candidate collection): ").append(executedUntilStage >= 2 ? "done" : "skipped").append("\n");
            sb.append("- Stage 3 (Applicability): not run in this standalone deterministic path\n");
            sb.append("- Stage 4 (Report generation): not run in this standalone deterministic path\n\n");

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
                sb.append("\n");
            }

            if (ok) {
                sb.append("Artifacts\n");
                if (executedUntilStage >= 1) {
                    sb.append("- Inventory: ").append(inventoryPath).append("\n");
                }
                if (executedUntilStage >= 2) {
                    sb.append("- Candidates: ").append(candidatePath).append("\n");
                }
            }

            return sb.toString().trim();
        }
    }

    public static Stage12Result run(CveReportRequest request, ObjectMapper objectMapper, int untilStage) throws IOException {
        var validation = CveReportValidationSupport.validateInputs(request, objectMapper);
        List<String> checks = new ArrayList<>(validation.checks());
        List<String> warnings = new ArrayList<>(validation.warnings());
        List<String> errors = new ArrayList<>(validation.errors());
        if (!errors.isEmpty()) {
            return new Stage12Result(false, "", "", "", 0, 0, 0, checks, warnings, errors);
        }

        JsonNode sbomRoot = readJson(request.sbomPath(), objectMapper);
        LicenseMapContext licenseMap = loadLicenseMapContext(
                request.licenseMapPath(),
                request.buildDirectory(),
                request.sbomPath(),
                objectMapper
        );
        BuildEvidenceContext buildEvidence = scanBuildEvidence(request.buildDirectory());

        String workspaceId = toSlug(request.productName() + "-" + request.productVersion());
        Path artifactDir = ensureArtifactDir(workspaceId);
        Path inventoryPath = artifactDir.resolve("inventory.json");
        Path candidatePath = artifactDir.resolve("cve_candidates.json");

        List<ObservedComponent> observedComponents = buildObservedInventory(
                sbomRoot,
                request,
                licenseMap,
                buildEvidence
        );
        writeJson(inventoryPath, objectMapper, Map.of(
                "productName", request.productName(),
                "productVersion", request.productVersion(),
                "generatedAt", LocalDate.now(SEOUL).toString(),
                "toolchainHint", detectToolchain(request.buildDirectory(), request.sbomPath()),
                "buildDirectory", request.buildDirectory(),
                "components", observedComponents
        ));
        checks.add("Stage 1 inventory built: " + observedComponents.size() + " observed components");

        if (untilStage <= 1) {
            checks.add("Stage 2 skipped by request (`--until-stage 1`).");
            return new Stage12Result(
                    true,
                    workspaceId,
                    inventoryPath.toString(),
                    "",
                    1,
                    observedComponents.size(),
                    0,
                    checks,
                    warnings,
                    errors
            );
        }

        CandidateCollectionResult candidateCollection = collectCveCandidates(
                sbomRoot,
                request,
                observedComponents,
                licenseMap,
                objectMapper
        );
        writeJson(candidatePath, objectMapper, Map.of(
                "inventoryPath", inventoryPath.toString(),
                "lookupSources", candidateCollection.lookupSources(),
                "candidateCount", candidateCollection.candidates().size(),
                "candidates", candidateCollection.candidates()
        ));
        for (String lookupWarning : candidateCollection.warnings()) {
            warnings.add(lookupWarning);
        }
        if (!candidateCollection.lookupSources().isEmpty()) {
            checks.add("Merged external advisory sources: " + String.join(", ", candidateCollection.lookupSources()));
        }
        checks.add("Stage 2 candidates built: " + candidateCollection.candidates().size() + " CVE candidates");

        return new Stage12Result(
                true,
                workspaceId,
                inventoryPath.toString(),
                candidatePath.toString(),
                2,
                observedComponents.size(),
                candidateCollection.candidates().size(),
                checks,
                warnings,
                errors
        );
    }

    private static List<ObservedComponent> buildObservedInventory(
            JsonNode sbomRoot,
            CveReportRequest request,
            LicenseMapContext licenseMap,
            BuildEvidenceContext buildEvidence
    ) {
        List<ObservedComponent> observedComponents = new ArrayList<>();
        for (JsonNode componentNode : iterable(sbomRoot.path("components"))) {
            String name = textOrDefault(componentNode, "name", "unknown-component");
            String version = textOrDefault(componentNode, "version", "unknown");
            String purl = textOrDefault(componentNode, "purl", "");
            List<String> evidence = new ArrayList<>();
            evidence.add("SBOM component entry from " + request.sbomPath());

            String bomRef = textOrDefault(componentNode, "bom-ref", "");
            if (!bomRef.isBlank()) {
                evidence.add("bom-ref: " + bomRef);
            }

            ComponentClassification classification = classifyComponent(componentNode, licenseMap, buildEvidence, name, version, purl);
            evidence.addAll(classification.evidence());

            observedComponents.add(new ObservedComponent(
                    name,
                    version,
                    purl,
                    classification.sourceType(),
                    classification.confidence(),
                    classification.presentInProduct(),
                    evidence
            ));
        }

        return observedComponents.stream()
                .collect(Collectors.toMap(
                        component -> normalize(component.componentName()) + "@" + component.version(),
                        component -> component,
                        CveReportDeterministicSupport::preferHigherConfidenceComponent,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .sorted(Comparator.comparing(ObservedComponent::componentName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static CandidateCollectionResult collectCveCandidates(
            JsonNode sbomRoot,
            CveReportRequest request,
            List<ObservedComponent> observedComponents,
            LicenseMapContext licenseMap,
            ObjectMapper objectMapper
    ) throws IOException {
        Map<String, ObservedComponent> componentsByBomRef = new LinkedHashMap<>();
        Map<String, ObservedComponent> componentsByPurl = new LinkedHashMap<>();
        Map<String, ObservedComponent> componentsByNameVersion = new LinkedHashMap<>();
        for (JsonNode componentNode : iterable(sbomRoot.path("components"))) {
            String bomRef = textOrDefault(componentNode, "bom-ref", "");
            String key = normalize(textOrDefault(componentNode, "name", "unknown-component")) + "@"
                    + textOrDefault(componentNode, "version", "unknown");
            ObservedComponent component = observedComponents.stream()
                    .filter(item -> (normalize(item.componentName()) + "@" + item.version()).equals(key))
                    .findFirst()
                    .orElse(null);
            if (component != null) {
                componentsByNameVersion.put(key, component);
                if (!bomRef.isBlank()) {
                    componentsByBomRef.put(bomRef, component);
                }
                if (!component.purl().isBlank()) {
                    componentsByPurl.put(component.purl(), component);
                }
            }
        }

        Map<String, CveCandidate> deduped = new LinkedHashMap<>();
        Map<String, KnownComponent> knownByPurl = new LinkedHashMap<>();
        Map<String, KnownComponent> knownByNameVersion = new LinkedHashMap<>();
        Map<String, KnownComponent> knownByName = new LinkedHashMap<>();
        indexKnownComponents(observedComponents, licenseMap, knownByPurl, knownByNameVersion, knownByName);
        mergeCycloneDxVulnerabilities(
                sbomRoot,
                "sbom",
                componentsByBomRef,
                componentsByPurl,
                componentsByNameVersion,
                deduped
        );

        for (Path grypePath : findSupplementalGrypeFiles(request.sbomPath())) {
            mergeCycloneDxVulnerabilities(
                    readJson(grypePath.toString(), objectMapper),
                    "grype",
                    componentsByBomRef,
                    componentsByPurl,
                    componentsByNameVersion,
                    deduped
            );
        }

        List<String> lookupSources = new ArrayList<>();
        List<String> lookupWarnings = new ArrayList<>();
        CveReportExternalAdvisorySupport.LookupResult osvLookup =
                CveReportExternalAdvisorySupport.lookupOsvAdvisories(licenseMap.thirdPartyByName(), objectMapper);
        for (CveReportExternalAdvisorySupport.ExternalAdvisory advisory : osvLookup.advisories()) {
            String candidateKey = advisory.cveId() + "::" + normalize(advisory.componentName()) + "::" + advisory.componentVersion();
            upsertCandidate(deduped, candidateKey, new CveCandidate(
                    advisory.cveId(),
                    advisory.componentName(),
                    advisory.componentVersion(),
                    advisory.matchedBy(),
                    advisory.severity(),
                    advisory.cvss(),
                    advisory.description(),
                    advisory.fixedVersions(),
                    advisory.referenceUrls(),
                    advisory.matchConfidence()
            ));
        }
        lookupSources.addAll(osvLookup.lookupSources());
        lookupWarnings.addAll(osvLookup.warnings());

        List<Path> externalAdvisoryFiles = findExternalAdvisoryFiles(request.sbomPath());
        if (externalAdvisoryFiles.isEmpty()) {
            if (!osvLookup.lookupSources().isEmpty()) {
                lookupWarnings.add("No local external advisory lookup files were found. Live OSV lookup is the only external advisory source in this run.");
            } else {
                lookupWarnings.add("No local external advisory lookup files were found. Stage 2 candidates currently rely on SBOM and supplemental grype data only.");
            }
        }
        for (Path advisoryPath : externalAdvisoryFiles) {
            mergeExternalAdvisoryLookup(
                    readJson(advisoryPath.toString(), objectMapper),
                    sourceNameFromAdvisoryFile(advisoryPath),
                    knownByPurl,
                    knownByNameVersion,
                    knownByName,
                    deduped
            );
            lookupSources.add(advisoryPath.toString());
        }

        return new CandidateCollectionResult(
                deduped.values().stream()
                        .sorted(Comparator.comparing(CveCandidate::cveId).thenComparing(CveCandidate::componentName))
                        .toList(),
                List.copyOf(lookupSources),
                List.copyOf(lookupWarnings)
        );
    }

    private static void mergeCycloneDxVulnerabilities(
            JsonNode root,
            String sourceName,
            Map<String, ObservedComponent> componentsByBomRef,
            Map<String, ObservedComponent> componentsByPurl,
            Map<String, ObservedComponent> componentsByNameVersion,
            Map<String, CveCandidate> deduped
    ) {
        for (JsonNode vulnNode : iterable(root.path("vulnerabilities"))) {
            String cveId = textOrDefault(vulnNode, "id", "UNKNOWN-CVE");
            List<String> references = collectAllReferences(vulnNode);
            String severity = firstRatingValue(vulnNode, "severity");
            String cvss = firstRatingScore(vulnNode);
            List<String> fixedVersions = collectAdvisoryValues(vulnNode, "fixedVersion");

            List<String> affects = collectAffectedRefs(vulnNode);
            if (affects.isEmpty()) {
                String candidateKey = cveId + "::unknown";
                upsertCandidate(deduped, candidateKey, new CveCandidate(
                        cveId,
                        "unknown-component",
                        "unknown",
                        List.of(sourceName),
                        severity,
                        cvss,
                        textOrDefault(vulnNode, "description", ""),
                        fixedVersions,
                        references,
                        "low"
                ));
                continue;
            }

            for (String affectRef : affects) {
                ObservedComponent component = resolveObservedComponent(
                        affectRef,
                        componentsByBomRef,
                        componentsByPurl,
                        componentsByNameVersion
                );
                if (component == null) {
                    continue;
                }

                String candidateKey = cveId + "::" + normalize(component.componentName()) + "::" + component.version();
                upsertCandidate(deduped, candidateKey, new CveCandidate(
                        cveId,
                        component.componentName(),
                        component.version(),
                        List.of(sourceName),
                        severity,
                        cvss,
                        textOrDefault(vulnNode, "description", ""),
                        fixedVersions,
                        references,
                        "grype".equals(sourceName) ? "high" : "medium"
                ));
            }
        }
    }

    private static void upsertCandidate(Map<String, CveCandidate> deduped, String key, CveCandidate incoming) {
        CveCandidate existing = deduped.get(key);
        if (existing == null) {
            deduped.put(key, incoming);
            return;
        }

        Set<String> mergedMatchedBy = new LinkedHashSet<>(existing.matchedBy());
        mergedMatchedBy.addAll(incoming.matchedBy());

        Set<String> mergedFixedVersions = new LinkedHashSet<>(existing.fixedVersions());
        mergedFixedVersions.addAll(incoming.fixedVersions());

        Set<String> mergedReferences = new LinkedHashSet<>(existing.referenceUrls());
        mergedReferences.addAll(incoming.referenceUrls());

        deduped.put(key, new CveCandidate(
                existing.cveId(),
                existing.componentName(),
                existing.componentVersion(),
                List.copyOf(mergedMatchedBy),
                preferNonUnknown(existing.severity(), incoming.severity()),
                preferLongerValue(existing.cvss(), incoming.cvss()),
                preferLongerValue(existing.description(), incoming.description()),
                List.copyOf(mergedFixedVersions),
                List.copyOf(mergedReferences),
                mergeConfidence(existing.matchConfidence(), incoming.matchConfidence())
        ));
    }

    private static ObservedComponent resolveObservedComponent(
            String affectRef,
            Map<String, ObservedComponent> componentsByBomRef,
            Map<String, ObservedComponent> componentsByPurl,
            Map<String, ObservedComponent> componentsByNameVersion
    ) {
        ObservedComponent byBomRef = componentsByBomRef.get(affectRef);
        if (byBomRef != null) {
            return byBomRef;
        }

        String normalizedAffectRef = normalizeRef(affectRef);
        for (Map.Entry<String, ObservedComponent> entry : componentsByBomRef.entrySet()) {
            if (normalizeRef(entry.getKey()).equals(normalizedAffectRef)) {
                return entry.getValue();
            }
        }

        ObservedComponent byPurl = componentsByPurl.get(stripPackageIdSuffix(affectRef));
        if (byPurl != null) {
            return byPurl;
        }

        String parsedNameVersion = nameVersionKeyFromPurl(affectRef);
        if (!parsedNameVersion.isBlank()) {
            return componentsByNameVersion.get(parsedNameVersion);
        }
        return null;
    }

    private static void indexKnownComponents(
            List<ObservedComponent> observedComponents,
            LicenseMapContext licenseMap,
            Map<String, KnownComponent> knownByPurl,
            Map<String, KnownComponent> knownByNameVersion,
            Map<String, KnownComponent> knownByName
    ) {
        for (ObservedComponent observedComponent : observedComponents) {
            putKnownComponent(
                    knownByPurl,
                    knownByNameVersion,
                    knownByName,
                    new KnownComponent(observedComponent.componentName(), observedComponent.version(), observedComponent.purl())
            );
        }

        for (Map.Entry<String, JsonNode> entry : licenseMap.thirdPartyByName().entrySet()) {
            String componentName = entry.getKey();
            JsonNode metadata = entry.getValue();
            putKnownComponent(
                    knownByPurl,
                    knownByNameVersion,
                    knownByName,
                    new KnownComponent(
                            componentName,
                            textOrDefault(metadata, "version", "unknown"),
                            textOrDefault(metadata, "purl", "")
                    )
            );
        }
    }

    private static void putKnownComponent(
            Map<String, KnownComponent> knownByPurl,
            Map<String, KnownComponent> knownByNameVersion,
            Map<String, KnownComponent> knownByName,
            KnownComponent component
    ) {
        String normalizedName = stripSrcSuffix(normalize(component.componentName()));
        if (normalizedName.isBlank()) {
            return;
        }
        knownByName.putIfAbsent(normalizedName, component);
        if (component.version() != null && !component.version().isBlank()) {
            knownByNameVersion.putIfAbsent(normalizedName + "@" + component.version(), component);
        }
        if (component.purl() != null && !component.purl().isBlank()) {
            knownByPurl.putIfAbsent(stripPackageIdSuffix(component.purl()), component);
        }
    }

    private static void mergeExternalAdvisoryLookup(
            JsonNode root,
            String fallbackSource,
            Map<String, KnownComponent> knownByPurl,
            Map<String, KnownComponent> knownByNameVersion,
            Map<String, KnownComponent> knownByName,
            Map<String, CveCandidate> deduped
    ) {
        for (JsonNode advisoryNode : advisoryEntries(root)) {
            String cveId = firstNonBlank(
                    textOrDefault(advisoryNode, "cveId", ""),
                    textOrDefault(advisoryNode, "id", ""),
                    textOrDefault(advisoryNode.path("advisory"), "id", "")
            );
            if (cveId.isBlank()) {
                continue;
            }

            CandidateMatch match = resolveKnownComponentFromAdvisory(
                    advisoryNode,
                    knownByPurl,
                    knownByNameVersion,
                    knownByName
            );
            KnownComponent component = match.component();
            List<String> matchedBy = collectMatchedBy(advisoryNode, fallbackSource);
            List<String> references = collectLookupReferenceUrls(advisoryNode);
            List<String> fixedVersions = collectLookupFixedVersions(advisoryNode);

            String candidateKey;
            if (component == null) {
                candidateKey = cveId + "::unknown";
                upsertCandidate(deduped, candidateKey, new CveCandidate(
                        cveId,
                        firstNonBlank(
                                textOrDefault(advisoryNode, "componentName", ""),
                                textOrDefault(advisoryNode, "component", ""),
                                textOrDefault(advisoryNode.path("component"), "name", ""),
                                "unknown-component"
                        ),
                        firstNonBlank(
                                textOrDefault(advisoryNode, "componentVersion", ""),
                                textOrDefault(advisoryNode, "version", ""),
                                textOrDefault(advisoryNode.path("component"), "version", ""),
                                "unknown"
                        ),
                        matchedBy,
                        firstNonBlank(textOrDefault(advisoryNode, "severity", ""), "UNKNOWN"),
                        firstNonBlank(textOrDefault(advisoryNode, "cvss", ""), textOrDefault(advisoryNode, "score", ""), ""),
                        firstNonBlank(
                                textOrDefault(advisoryNode, "description", ""),
                                textOrDefault(advisoryNode.path("advisory"), "summary", ""),
                                ""
                        ),
                        fixedVersions,
                        references,
                        "low"
                ));
                continue;
            }

            candidateKey = cveId + "::" + normalize(component.componentName()) + "::" + component.version();
            upsertCandidate(deduped, candidateKey, new CveCandidate(
                    cveId,
                    component.componentName(),
                    component.version(),
                    matchedBy,
                    firstNonBlank(textOrDefault(advisoryNode, "severity", ""), "UNKNOWN"),
                    firstNonBlank(textOrDefault(advisoryNode, "cvss", ""), textOrDefault(advisoryNode, "score", ""), ""),
                    firstNonBlank(
                            textOrDefault(advisoryNode, "description", ""),
                            textOrDefault(advisoryNode.path("advisory"), "summary", ""),
                            ""
                    ),
                    fixedVersions,
                    references,
                    match.confidence()
            ));
        }
    }

    private static List<JsonNode> advisoryEntries(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return List.of();
        }
        if (root.isArray()) {
            return iterable(root);
        }
        if (root.path("advisories").isArray()) {
            return iterable(root.path("advisories"));
        }
        if (root.path("vulnerabilities").isArray()) {
            return iterable(root.path("vulnerabilities"));
        }
        return List.of();
    }

    private static CandidateMatch resolveKnownComponentFromAdvisory(
            JsonNode advisoryNode,
            Map<String, KnownComponent> knownByPurl,
            Map<String, KnownComponent> knownByNameVersion,
            Map<String, KnownComponent> knownByName
    ) {
        String purl = firstNonBlank(
                textOrDefault(advisoryNode, "purl", ""),
                textOrDefault(advisoryNode.path("component"), "purl", "")
        );
        if (!purl.isBlank()) {
            KnownComponent byPurl = knownByPurl.get(stripPackageIdSuffix(purl));
            if (byPurl != null) {
                return new CandidateMatch(byPurl, "high");
            }
        }

        String componentName = firstNonBlank(
                textOrDefault(advisoryNode, "componentName", ""),
                textOrDefault(advisoryNode, "component", ""),
                textOrDefault(advisoryNode.path("component"), "name", "")
        );
        String version = firstNonBlank(
                textOrDefault(advisoryNode, "componentVersion", ""),
                textOrDefault(advisoryNode, "version", ""),
                textOrDefault(advisoryNode.path("component"), "version", "")
        );

        if (!componentName.isBlank() && !version.isBlank()) {
            KnownComponent byNameVersion = knownByNameVersion.get(stripSrcSuffix(normalize(componentName)) + "@" + version);
            if (byNameVersion != null) {
                return new CandidateMatch(byNameVersion, "high");
            }
        }

        if (!componentName.isBlank()) {
            KnownComponent byName = knownByName.get(stripSrcSuffix(normalize(componentName)));
            if (byName != null) {
                return new CandidateMatch(byName, version.isBlank() ? "medium" : "high");
            }
            return new CandidateMatch(
                    new KnownComponent(componentName, version.isBlank() ? "unknown" : version, purl),
                    version.isBlank() ? "low" : "medium"
            );
        }

        return new CandidateMatch(null, "low");
    }

    private static List<String> collectMatchedBy(JsonNode advisoryNode, String fallbackSource) {
        Set<String> matchedBy = new LinkedHashSet<>();
        for (JsonNode node : iterable(advisoryNode.path("matchedBy"))) {
            String value = node.asText("").trim();
            if (!value.isBlank()) {
                matchedBy.add(normalizeLookupSource(value));
            }
        }

        String source = firstNonBlank(
                textOrDefault(advisoryNode, "source", ""),
                textOrDefault(advisoryNode.path("advisory"), "source", ""),
                fallbackSource
        );
        if (!source.isBlank()) {
            matchedBy.add(normalizeLookupSource(source));
        }
        return List.copyOf(matchedBy);
    }

    private static List<String> collectLookupReferenceUrls(JsonNode advisoryNode) {
        Set<String> refs = new LinkedHashSet<>();
        for (JsonNode node : iterable(advisoryNode.path("referenceUrls"))) {
            String value = node.asText("").trim();
            if (!value.isBlank()) {
                refs.add(value);
            }
        }
        for (JsonNode node : iterable(advisoryNode.path("references"))) {
            if (node.isTextual()) {
                String value = node.asText("").trim();
                if (!value.isBlank()) {
                    refs.add(value);
                }
                continue;
            }
            String url = textOrDefault(node, "url", "");
            if (!url.isBlank()) {
                refs.add(url);
            }
        }
        for (JsonNode node : iterable(advisoryNode.path("advisories"))) {
            String url = textOrDefault(node, "url", "");
            if (!url.isBlank()) {
                refs.add(url);
            }
        }
        String url = textOrDefault(advisoryNode, "url", "");
        if (!url.isBlank()) {
            refs.add(url);
        }
        return List.copyOf(refs);
    }

    private static List<String> collectLookupFixedVersions(JsonNode advisoryNode) {
        Set<String> fixedVersions = new LinkedHashSet<>();
        String fixedVersion = textOrDefault(advisoryNode, "fixedVersion", "");
        if (!fixedVersion.isBlank()) {
            fixedVersions.add(fixedVersion);
        }
        for (JsonNode node : iterable(advisoryNode.path("fixedVersions"))) {
            String value = node.asText("").trim();
            if (!value.isBlank()) {
                fixedVersions.add(value);
            }
        }
        for (JsonNode node : iterable(advisoryNode.path("advisories"))) {
            String value = textOrDefault(node, "fixedVersion", "");
            if (!value.isBlank()) {
                fixedVersions.add(value);
            }
        }
        return List.copyOf(fixedVersions);
    }

    private static LicenseMapContext loadLicenseMapContext(
            String licenseMapPath,
            String buildDirectory,
            String sbomPath,
            ObjectMapper objectMapper
    ) throws IOException {
        JsonNode root = readJson(licenseMapPath, objectMapper);
        Map<String, JsonNode> thirdPartyByName = new LinkedHashMap<>();
        root.path("third_party").fields().forEachRemaining(entry -> thirdPartyByName.put(normalize(entry.getKey()), entry.getValue()));

        Set<String> thirdPartyNames = thirdPartyByName.keySet();
        Set<String> coreLibraryNames = new LinkedHashSet<>();
        for (JsonNode coreNode : iterable(root.path("core_libraries"))) {
            String normalized = normalize(coreNode.asText(""));
            if (!normalized.isBlank()) {
                coreLibraryNames.add(stripSrcSuffix(normalized));
            }
        }

        Set<String> excludedForToolchain = new LinkedHashSet<>();
        String toolchain = detectToolchain(buildDirectory, sbomPath);
        if (toolchain.contains("autotalks") || toolchain.contains("hed")) {
            for (JsonNode excludedNode : iterable(root.path("exclude_for_autotalks"))) {
                String normalized = normalize(excludedNode.asText(""));
                if (!normalized.isBlank()) {
                    excludedForToolchain.add(stripSrcSuffix(normalized));
                }
            }
        }

        return new LicenseMapContext(
                Set.copyOf(thirdPartyNames),
                Set.copyOf(coreLibraryNames),
                Set.copyOf(excludedForToolchain),
                Map.copyOf(thirdPartyByName)
        );
    }

    private static ComponentClassification classifyComponent(
            JsonNode componentNode,
            LicenseMapContext licenseMap,
            BuildEvidenceContext buildEvidence,
            String name,
            String version,
            String purl
    ) {
        String normalizedName = stripSrcSuffix(normalize(name));
        List<String> evidence = new ArrayList<>();
        boolean isThirdPartyKnown = licenseMap.thirdPartyNames().contains(normalizedName);
        boolean isCoreLibrary = licenseMap.coreLibraryNames().contains(normalizedName);
        boolean excludedForToolchain = licenseMap.excludedForToolchain().contains(normalizedName);
        boolean minimalSyntheticShape = looksSyntheticFromLicenseMap(componentNode);
        boolean exactThirdPartyMetadata = matchesLicenseMapMetadata(licenseMap.thirdPartyByName().get(normalizedName), version, purl);
        List<String> buildEvidenceMatches = findBuildEvidence(buildEvidence, normalizedName, name, purl);

        if (excludedForToolchain) {
            evidence.add("Toolchain exclusion matched from license_map exclude_for_autotalks.");
            return new ComponentClassification("manual_map", "low", false, evidence);
        }

        if (isThirdPartyKnown) {
            evidence.add("Matched license_map third_party entry for component identification.");
        }
        if (isCoreLibrary) {
            evidence.add("Matched license_map core_libraries entry.");
        }
        if (minimalSyntheticShape) {
            evidence.add("Component shape looks synthetic: minimal fields with no properties/evidence/externalReferences/cpe.");
        }
        if (exactThirdPartyMetadata) {
            evidence.add("Version/purl match the canonical license_map third_party metadata.");
        }
        evidence.addAll(buildEvidenceMatches);

        if (!buildEvidenceMatches.isEmpty()) {
            evidence.add("Build artifact scan found matching file or path evidence.");
            return new ComponentClassification("binary_scan", "high", true, evidence);
        }

        if (isThirdPartyKnown && minimalSyntheticShape && exactThirdPartyMetadata) {
            evidence.add("Treating this component as likely injected by postprocess add_missing_core_libraries(), not as directly observed evidence.");
            return new ComponentClassification("manual_map", "low", false, evidence);
        }

        if (hasStrongSbomEvidence(componentNode)) {
            evidence.add("Component contains native SBOM evidence fields (properties/evidence/externalReferences/cpe/hashes).");
            return new ComponentClassification("sbom", "high", true, evidence);
        }

        if (isCoreLibrary || isThirdPartyKnown) {
            evidence.add("Known library from license_map, but direct observation evidence is weaker than preferred.");
            return new ComponentClassification("sbom", "medium", true, evidence);
        }

        evidence.add("No license_map override triggered; treating component as SBOM-observed.");
        return new ComponentClassification("sbom", "high", true, evidence);
    }

    private static BuildEvidenceContext scanBuildEvidence(String buildDirectory) {
        if (buildDirectory == null || buildDirectory.isBlank()) {
            return new BuildEvidenceContext("", List.of());
        }

        Path buildPath = Paths.get(buildDirectory).normalize();
        if (!Files.exists(buildPath) || !Files.isDirectory(buildPath)) {
            return new BuildEvidenceContext(buildDirectory, List.of());
        }

        List<String> indexedPaths = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(buildPath, 6)) {
            stream.filter(Files::isRegularFile)
                    .limit(5000)
                    .forEach(path -> indexedPaths.add(buildPath.relativize(path).toString().replace('\\', '/').toLowerCase(Locale.ROOT)));
        } catch (IOException ignored) {
            return new BuildEvidenceContext(buildDirectory, indexedPaths);
        }

        return new BuildEvidenceContext(buildDirectory, indexedPaths);
    }

    private static List<String> findBuildEvidence(BuildEvidenceContext context, String normalizedName, String originalName, String purl) {
        if (context.indexedPaths().isEmpty()) {
            return List.of();
        }

        Set<String> aliases = componentAliases(normalizedName, originalName, purl);
        List<String> matches = new ArrayList<>();
        for (String indexedPath : context.indexedPaths()) {
            for (String alias : aliases) {
                if (!alias.isBlank() && indexedPath.contains(alias)) {
                    matches.add("Build artifact match: " + indexedPath + " (alias=" + alias + ")");
                    break;
                }
            }
            if (matches.size() >= 3) {
                break;
            }
        }
        return matches;
    }

    private static Set<String> componentAliases(String normalizedName, String originalName, String purl) {
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(normalizedName);

        String rawName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (!rawName.isBlank()) {
            aliases.add(rawName);
            aliases.add(rawName.replace("-", ""));
            aliases.add(rawName.replace("_", ""));
        }

        if (purl != null && !purl.isBlank()) {
            String cleaned = stripPackageIdSuffix(purl).toLowerCase(Locale.ROOT);
            int at = cleaned.lastIndexOf('@');
            int slash = cleaned.lastIndexOf('/', at >= 0 ? at : cleaned.length());
            if (slash >= 0) {
                String purlName = cleaned.substring(slash + 1, at >= 0 ? at : cleaned.length());
                aliases.add(purlName);
                aliases.add(purlName.replace("-", ""));
                aliases.add(purlName.replace("_", ""));
            }
        }

        if ("openssl".equals(normalizedName)) {
            aliases.add("openssl");
            aliases.add("libssl");
            aliases.add("libcrypto");
            aliases.add("/apps/openssl");
        }
        if ("curl".equals(normalizedName)) {
            aliases.add("libcurl");
        }
        if ("jsoncpp".equals(normalizedName)) {
            aliases.add("libjsoncpp");
        }
        if ("spdlog".equals(normalizedName)) {
            aliases.add("libspdlog");
        }
        if ("leveldb".equals(normalizedName)) {
            aliases.add("libleveldb");
        }

        return aliases.stream()
                .filter(alias -> alias != null && !alias.isBlank())
                .map(alias -> alias.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean looksSyntheticFromLicenseMap(JsonNode componentNode) {
        return isEmptyNode(componentNode.get("properties"))
                && isEmptyNode(componentNode.get("evidence"))
                && isEmptyNode(componentNode.get("externalReferences"))
                && isEmptyNode(componentNode.get("hashes"))
                && isBlankNode(componentNode.get("cpe"))
                && isBlankNode(componentNode.get("description"));
    }

    private static boolean matchesLicenseMapMetadata(JsonNode licenseInfo, String version, String purl) {
        if (licenseInfo == null || licenseInfo.isMissingNode() || licenseInfo.isNull()) {
            return false;
        }
        String expectedVersion = textOrDefault(licenseInfo, "version", "");
        String expectedPurl = textOrDefault(licenseInfo, "purl", "");
        boolean versionMatches = expectedVersion.isBlank() || Objects.equals(expectedVersion, version);
        boolean purlMatches = expectedPurl.isBlank() || Objects.equals(expectedPurl, purl);
        return versionMatches && purlMatches;
    }

    private static boolean hasStrongSbomEvidence(JsonNode componentNode) {
        return !isEmptyNode(componentNode.get("properties"))
                || !isEmptyNode(componentNode.get("evidence"))
                || !isEmptyNode(componentNode.get("externalReferences"))
                || !isEmptyNode(componentNode.get("hashes"))
                || !isBlankNode(componentNode.get("cpe"));
    }

    private static boolean isEmptyNode(JsonNode node) {
        return node == null || node.isNull() || (node.isArray() && node.isEmpty()) || (node.isObject() && node.isEmpty());
    }

    private static boolean isBlankNode(JsonNode node) {
        return node == null || node.isNull() || node.asText("").isBlank();
    }

    private static ObservedComponent preferHigherConfidenceComponent(ObservedComponent left, ObservedComponent right) {
        int leftScore = confidenceScore(left);
        int rightScore = confidenceScore(right);
        if (rightScore > leftScore) {
            return right;
        }
        if (leftScore > rightScore) {
            return left;
        }
        if (right.presentInProduct() && !left.presentInProduct()) {
            return right;
        }
        return left;
    }

    private static int confidenceScore(ObservedComponent component) {
        int score = switch (component.confidence()) {
            case "high" -> 3;
            case "medium" -> 2;
            default -> 1;
        };
        if (component.presentInProduct()) {
            score += 10;
        }
        return score;
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

    private static List<String> collectReferences(JsonNode vulnNode) {
        Set<String> refs = new LinkedHashSet<>();
        for (JsonNode referenceNode : iterable(vulnNode.path("references"))) {
            String url = textOrDefault(referenceNode, "url", "");
            if (!url.isBlank()) {
                refs.add(url);
            }
        }
        return List.copyOf(refs);
    }

    private static List<String> collectAllReferences(JsonNode vulnNode) {
        Set<String> refs = new LinkedHashSet<>(collectReferences(vulnNode));
        String sourceUrl = textOrDefault(vulnNode.path("source"), "url", "");
        if (!sourceUrl.isBlank()) {
            refs.add(sourceUrl);
        }
        for (JsonNode advisoryNode : iterable(vulnNode.path("advisories"))) {
            String advisoryUrl = textOrDefault(advisoryNode, "url", "");
            if (!advisoryUrl.isBlank()) {
                refs.add(advisoryUrl);
            }
        }
        return List.copyOf(refs);
    }

    private static List<String> collectAdvisoryValues(JsonNode vulnNode, String fieldName) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode advisoryNode : iterable(vulnNode.path("advisories"))) {
            String value = textOrDefault(advisoryNode, fieldName, "");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String firstRatingValue(JsonNode vulnNode, String fieldName) {
        JsonNode ratings = vulnNode.path("ratings");
        if (!ratings.isArray() || ratings.isEmpty()) {
            return "UNKNOWN";
        }
        return textOrDefault(ratings.get(0), fieldName, "UNKNOWN");
    }

    private static String firstRatingScore(JsonNode vulnNode) {
        JsonNode ratings = vulnNode.path("ratings");
        if (!ratings.isArray() || ratings.isEmpty()) {
            return "";
        }
        JsonNode scoreNode = ratings.get(0).get("score");
        return scoreNode == null || scoreNode.isNull() ? "" : scoreNode.asText("");
    }

    private static List<String> collectAffectedRefs(JsonNode vulnNode) {
        List<String> affects = new ArrayList<>();
        for (JsonNode affectNode : iterable(vulnNode.path("affects"))) {
            String ref;
            JsonNode refNode = affectNode.get("ref");
            if (refNode == null || refNode.isNull()) {
                ref = "";
            } else if (refNode.isTextual()) {
                ref = refNode.asText("");
            } else {
                ref = textOrDefault(refNode, "bom-ref", "");
            }
            if (!ref.isBlank()) {
                affects.add(ref);
            }
        }
        return affects;
    }

    private static List<Path> findSupplementalGrypeFiles(String sbomPath) {
        Path sbom = Paths.get(sbomPath).normalize();
        List<Path> candidates = List.of(
                sbom.getParent() != null ? sbom.getParent().resolve("sp_grype.json") : null,
                sbom.getParent() != null ? sbom.getParent().resolve("ossl_grype.json") : null,
                Paths.get("sbom/temp/sp_grype.json"),
                Paths.get("sbom/temp/ossl_grype.json")
        );

        List<Path> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Path normalized = candidate.normalize();
            if (Files.exists(normalized) && seen.add(normalized.toString()) && !normalized.equals(sbom)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static List<Path> findExternalAdvisoryFiles(String sbomPath) {
        Path sbom = Paths.get(sbomPath).normalize();
        List<Path> candidates = List.of(
                sbom.getParent() != null ? sbom.getParent().resolve("external_advisories.json") : null,
                sbom.getParent() != null ? sbom.getParent().resolve("advisory_lookup.json") : null,
                sbom.getParent() != null ? sbom.getParent().resolve("osv_advisories.json") : null,
                sbom.getParent() != null ? sbom.getParent().resolve("nvd_advisories.json") : null,
                sbom.getParent() != null ? sbom.getParent().resolve("vendor_advisories.json") : null,
                Paths.get("sbom/temp/external_advisories.json"),
                Paths.get("sbom/temp/advisory_lookup.json"),
                Paths.get("sbom/temp/osv_advisories.json"),
                Paths.get("sbom/temp/nvd_advisories.json"),
                Paths.get("sbom/temp/vendor_advisories.json")
        );

        List<Path> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Path normalized = candidate.normalize();
            if (Files.exists(normalized) && seen.add(normalized.toString()) && !normalized.equals(sbom)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String sourceNameFromAdvisoryFile(Path advisoryPath) {
        String fileName = advisoryPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.contains("osv")) {
            return "osv";
        }
        if (fileName.contains("nvd")) {
            return "nvd_version_match";
        }
        if (fileName.contains("vendor")) {
            return "vendor_advisory";
        }
        return "external_advisory";
    }

    private static String normalizeRef(String ref) {
        return stripPackageIdSuffix(ref).toLowerCase(Locale.ROOT);
    }

    private static String stripPackageIdSuffix(String ref) {
        int idx = ref.indexOf("?package-id=");
        return idx >= 0 ? ref.substring(0, idx) : ref;
    }

    private static String nameVersionKeyFromPurl(String ref) {
        String cleaned = stripPackageIdSuffix(ref);
        int atIndex = cleaned.lastIndexOf('@');
        if (atIndex < 0 || atIndex == cleaned.length() - 1) {
            return "";
        }
        String version = cleaned.substring(atIndex + 1);
        int slashIndex = cleaned.lastIndexOf('/', atIndex);
        int colonIndex = cleaned.lastIndexOf(':', atIndex);
        int nameStart = Math.max(slashIndex, colonIndex);
        if (nameStart < 0 || nameStart >= atIndex) {
            return "";
        }
        String name = cleaned.substring(nameStart + 1, atIndex);
        return normalize(name) + "@" + version;
    }

    private static String preferNonUnknown(String left, String right) {
        if (left == null || left.isBlank() || "UNKNOWN".equalsIgnoreCase(left)) {
            return right;
        }
        return left;
    }

    private static String preferLongerValue(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        if (right != null && right.length() > left.length()) {
            return right;
        }
        return left;
    }

    private static String mergeConfidence(String left, String right) {
        return Math.max(confidenceRank(left), confidenceRank(right)) >= confidenceRank("high") ? "high"
                : Math.max(confidenceRank(left), confidenceRank(right)) >= confidenceRank("medium") ? "medium"
                : "low";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeLookupSource(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("osv")) {
            return "osv";
        }
        if (normalized.contains("nvd")) {
            return "nvd_version_match";
        }
        if (normalized.contains("vendor")) {
            return "vendor_advisory";
        }
        if (normalized.contains("manual")) {
            return "manual_rule";
        }
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.replace(' ', '_');
    }

    private static int confidenceRank(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "high" -> 3;
            case "medium" -> 2;
            default -> 1;
        };
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
        normalized = normalized.replaceAll("\\s+", "-");
        return normalized.replaceAll("[^a-z0-9._+-]", "");
    }

    private static String stripSrcSuffix(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("-src") ? value.substring(0, value.length() - 4) : value;
    }

    private static String detectToolchain(String buildDirectory, String sbomPath) {
        String joined = ((buildDirectory == null ? "" : buildDirectory) + " " + (sbomPath == null ? "" : sbomPath))
                .toLowerCase(Locale.ROOT);
        if (joined.contains("autotalks") || joined.contains("hed")) {
            return "autotalks";
        }
        if (joined.contains("etsi102941")) {
            return "etsi102941";
        }
        return "generic";
    }

    private static JsonNode readJson(String path, ObjectMapper objectMapper) throws IOException {
        return objectMapper.readTree(Files.readString(Paths.get(path).normalize()));
    }

    private static void writeJson(Path path, ObjectMapper objectMapper, Object payload) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
    }

    private static Path ensureArtifactDir(String workspaceId) throws IOException {
        Path path = Paths.get("output").resolve(AGENT_NAME).resolve(toSlug(workspaceId)).resolve("artifacts");
        Files.createDirectories(path);
        return path;
    }

    private static String toSlug(String input) {
        if (input == null || input.isBlank()) {
            return "default";
        }

        String name = input;
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            name = name.substring(0, lastDot);
        }

        name = Paths.get(name).getFileName().toString();
        String noWhitespace = WHITESPACE.matcher(name).replaceAll("-");
        String normalized = Normalizer.normalize(noWhitespace, Normalizer.Form.NFD);
        String slug = NON_LATIN.matcher(normalized).replaceAll("");
        String result = slug.toLowerCase(Locale.ENGLISH).replaceAll("-+", "-").replaceAll("^-|-$", "");

        if (result.isBlank()) {
            return "ws-" + Math.abs(input.hashCode() % 10000);
        }
        if (result.length() > 35) {
            return result.substring(0, 30) + "-" + String.format("%04x", result.hashCode() & 0xFFFF);
        }
        return result;
    }
}
