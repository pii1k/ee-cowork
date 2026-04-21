package io.autocrypt.eeTeam.cowork.cvereportagent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocrypt.eeTeam.cowork.cvereportagent.domain.CveReportRequest;
import io.autocrypt.eeTeam.cowork.cvereportagent.domain.CveReportResult;
import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;
import io.autocrypt.jwlee.cowork.core.tools.CoreWorkspaceProvider;
import io.autocrypt.jwlee.cowork.core.tools.CoworkLogger;
import io.autocrypt.jwlee.cowork.core.tools.FileReadTool;
import io.autocrypt.jwlee.cowork.core.tools.FileWriteTool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Agent(description = "Collects observed components from SBOM data, gathers CVE candidates, and produces an evidence-backed CVE report.")
@Component
public class CveReportAgent {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String AGENT_NAME = "cvereportagent";

    private final FileReadTool readTool;
    private final FileWriteTool writeTool;
    private final CoreWorkspaceProvider workspaceProvider;
    private final CoworkLogger logger;
    private final ObjectMapper objectMapper;
    private final PromptProvider promptProvider;

    public CveReportAgent(
            FileReadTool readTool,
            FileWriteTool writeTool,
            CoreWorkspaceProvider workspaceProvider,
            CoworkLogger logger,
            ObjectMapper objectMapper,
            PromptProvider promptProvider
    ) {
        this.readTool = readTool;
        this.writeTool = writeTool;
        this.workspaceProvider = workspaceProvider;
        this.logger = logger;
        this.objectMapper = objectMapper;
        this.promptProvider = promptProvider;
    }

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

    public record CveAssessment(
            String cveId,
            String componentName,
            String status,
            String rationale,
            List<String> evidence,
            List<String> source,
            String owner,
            String reviewDate
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

    public record EvidenceInterpretation(
            String summary,
            String confidence,
            List<String> missingEvidence
    ) {}

    public record ApplicabilityRecommendation(
            String recommendedStatus,
            String rationale,
            String confidence,
            List<String> missingEvidence
    ) {}

    @State
    public record InventoryState(
            CveReportRequest request,
            String workspaceId,
            List<ObservedComponent> observedComponents,
            String inventoryPath
    ) {}

    @State
    public record CandidateCollectionState(
            CveReportRequest request,
            String workspaceId,
            List<ObservedComponent> observedComponents,
            String inventoryPath,
            List<CveCandidate> candidates,
            String candidatePath
    ) {}

    @State
    public record AssessmentState(
            CveReportRequest request,
            String workspaceId,
            List<ObservedComponent> observedComponents,
            List<CveCandidate> candidates,
            List<CveAssessment> assessments,
            String assessmentPath
    ) {}

    @Action(description = "Stage 1: Build observed component inventory from the input SBOM.")
    public InventoryState buildObservedInventory(CveReportRequest request) throws IOException {
        logger.info("CveReportAgent", "Building observed inventory from SBOM: " + request.sbomPath());

        JsonNode sbomRoot = readJson(request.sbomPath());
        LicenseMapContext licenseMap = loadLicenseMapContext(request.licenseMapPath(), request.buildDirectory(), request.sbomPath());
        BuildEvidenceContext buildEvidence = scanBuildEvidence(request.buildDirectory());
        String workspaceId = workspaceProvider.toSlug(request.productName() + "-" + request.productVersion());
        Path artifactDir = workspaceProvider.getSubPath(AGENT_NAME, workspaceId, CoreWorkspaceProvider.SubCategory.ARTIFACTS);
        Path inventoryPath = artifactDir.resolve("inventory.json");

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

        observedComponents = observedComponents.stream()
                .collect(Collectors.toMap(
                        component -> normalize(component.componentName()) + "@" + component.version(),
                        component -> component,
                        this::preferHigherConfidenceComponent,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .sorted(Comparator.comparing(ObservedComponent::componentName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        writeJson(inventoryPath, Map.of(
                "productName", request.productName(),
                "productVersion", request.productVersion(),
                "generatedAt", LocalDate.now(SEOUL).toString(),
                "toolchainHint", detectToolchain(request.buildDirectory(), request.sbomPath()),
                "buildDirectory", request.buildDirectory(),
                "components", observedComponents
        ));

        return new InventoryState(request, workspaceId, observedComponents, inventoryPath.toString());
    }

    @Action(description = "Stage 2: Collect CVE candidates from SBOM vulnerability entries.")
    public CandidateCollectionState collectCveCandidates(InventoryState state) throws IOException {
        logger.info("CveReportAgent", "Collecting CVE candidates from SBOM vulnerability data...");

        JsonNode sbomRoot = readJson(state.request().sbomPath());
        Path artifactDir = workspaceProvider.getSubPath(AGENT_NAME, state.workspaceId(), CoreWorkspaceProvider.SubCategory.ARTIFACTS);
        Path candidatePath = artifactDir.resolve("cve_candidates.json");

        Map<String, ObservedComponent> componentsByBomRef = new LinkedHashMap<>();
        Map<String, ObservedComponent> componentsByPurl = new LinkedHashMap<>();
        Map<String, ObservedComponent> componentsByNameVersion = new LinkedHashMap<>();
        for (JsonNode componentNode : iterable(sbomRoot.path("components"))) {
            String bomRef = textOrDefault(componentNode, "bom-ref", "");
            if (bomRef.isBlank()) {
                // continue scanning purl/name mappings below
            }
            String key = normalize(textOrDefault(componentNode, "name", "unknown-component")) + "@"
                    + textOrDefault(componentNode, "version", "unknown");
            ObservedComponent component = state.observedComponents().stream()
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
        mergeCycloneDxVulnerabilities(
                sbomRoot,
                "sbom",
                componentsByBomRef,
                componentsByPurl,
                componentsByNameVersion,
                deduped
        );

        for (Path grypePath : findSupplementalGrypeFiles(state.request().sbomPath())) {
            logger.info("CveReportAgent", "Merging supplemental grype vulnerability source: " + grypePath);
            mergeCycloneDxVulnerabilities(
                    readJson(grypePath.toString()),
                    "grype",
                    componentsByBomRef,
                    componentsByPurl,
                    componentsByNameVersion,
                    deduped
            );
        }

        List<CveCandidate> candidates = deduped.values().stream()
                .sorted(Comparator.comparing(CveCandidate::cveId).thenComparing(CveCandidate::componentName))
                .toList();

        writeJson(candidatePath, Map.of(
                "inventoryPath", state.inventoryPath(),
                "candidateCount", candidates.size(),
                "candidates", candidates
        ));

        return new CandidateCollectionState(
                state.request(),
                state.workspaceId(),
                state.observedComponents(),
                state.inventoryPath(),
                candidates,
                candidatePath.toString()
        );
    }

    private void mergeCycloneDxVulnerabilities(
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

    private void upsertCandidate(Map<String, CveCandidate> deduped, String key, CveCandidate incoming) {
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

    private ObservedComponent resolveObservedComponent(
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

    private List<String> collectAffectedRefs(JsonNode vulnNode) {
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

    @Action(description = "Stage 3: Assess applicability with deterministic MVP rules.")
    public AssessmentState assessApplicability(CandidateCollectionState state, Ai ai) throws IOException {
        logger.info("CveReportAgent", "Assessing applicability for collected CVE candidates...");

        Path artifactDir = workspaceProvider.getSubPath(AGENT_NAME, state.workspaceId(), CoreWorkspaceProvider.SubCategory.ARTIFACTS);
        Path assessmentPath = artifactDir.resolve("cve_assessment.json");
        Set<String> patchedIds = parseTaggedCves(state.request().analystNotes(), "patched");
        Set<String> fixedIds = parseTaggedCves(state.request().analystNotes(), "fixed");
        Set<String> affectedIds = parseTaggedCves(state.request().analystNotes(), "affected");
        Set<String> notPresentIds = parseTaggedCves(state.request().analystNotes(), "notpresent");
        Set<String> unusedIds = parseTaggedCves(state.request().analystNotes(), "unused");

        List<CveAssessment> assessments = new ArrayList<>();
        for (CveCandidate candidate : state.candidates()) {
            ObservedComponent observed = state.observedComponents().stream()
                    .filter(component -> normalize(component.componentName()).equals(normalize(candidate.componentName()))
                            && Objects.equals(component.version(), candidate.componentVersion()))
                    .findFirst()
                    .orElse(null);

            String status;
            String rationale;
            List<String> evidence = new ArrayList<>();
            evidence.add("Candidate source: " + String.join(", ", candidate.matchedBy()));
            evidence.addAll(candidate.referenceUrls());
            boolean hasBuildEvidence = observed != null && observed.evidence().stream().anyMatch(item -> item.startsWith("Build artifact match:"));
            boolean observedHighConfidence = observed != null && "high".equalsIgnoreCase(observed.confidence());
            boolean sourceIncludesGrype = candidate.matchedBy().contains("grype");
            boolean needsAiRecommendation = observed != null
                    && observed.presentInProduct()
                    && !patchedIds.contains(candidate.cveId())
                    && !fixedIds.contains(candidate.cveId())
                    && !affectedIds.contains(candidate.cveId())
                    && !notPresentIds.contains(candidate.cveId())
                    && !unusedIds.contains(candidate.cveId());

            if (notPresentIds.contains(candidate.cveId())) {
                status = "not_affected_not_present";
                rationale = "분석자 노트에서 제품 미포함으로 명시되어 있어 비영향으로 분류했습니다.";
                evidence.add("Analyst notes tagged as notpresent for " + candidate.cveId());
            } else if (unusedIds.contains(candidate.cveId())) {
                status = "not_affected_unused_code_path";
                rationale = "분석자 노트에서 취약 기능 또는 코드 경로가 사용되지 않는다고 명시되어 있어 비영향으로 분류했습니다.";
                evidence.add("Analyst notes tagged as unused for " + candidate.cveId());
            } else if (patchedIds.contains(candidate.cveId())) {
                status = "not_affected_patched";
                rationale = "분석자 노트에서 패치 또는 백포트 적용이 명시되어 있어 비영향으로 분류했습니다.";
                evidence.add("Analyst notes tagged as patched for " + candidate.cveId());
            } else if (fixedIds.contains(candidate.cveId())) {
                status = "fixed";
                rationale = "분석자 노트에서 해당 CVE가 수정 완료된 것으로 표시되어 fixed 상태로 분류했습니다.";
                evidence.add("Analyst notes tagged as fixed for " + candidate.cveId());
            } else if (affectedIds.contains(candidate.cveId())) {
                status = "affected";
                rationale = "분석자 노트에서 실제 영향 대상으로 명시되어 affected 상태로 분류했습니다.";
                evidence.add("Analyst notes tagged as affected for " + candidate.cveId());
            } else if (observed == null || !observed.presentInProduct()) {
                status = "not_affected_not_present";
                rationale = "Observed inventory에 해당 컴포넌트가 확인되지 않아 현재 제품 영향 대상으로 보지 않았습니다.";
            } else if (hasBuildEvidence && observedHighConfidence && sourceIncludesGrype) {
                status = "affected";
                rationale = "빌드 산출물에서 컴포넌트 존재 흔적이 확인되었고 grype candidate와도 일치하여 우선 영향 가능성이 높은 항목으로 분류했습니다.";
                evidence.add("Observed component has build artifact evidence and grype-backed candidate.");
                evidence.addAll(observed.evidence());
            } else if (!hasBuildEvidence && sourceIncludesGrype && !candidate.fixedVersions().isEmpty()) {
                status = "under_investigation";
                rationale = "grype candidate와 수정 버전 정보는 있으나 현재 빌드 산출물 증거가 부족하여 추가 확인이 필요합니다.";
                evidence.add("Candidate has fixedVersion hints but lacks direct build evidence.");
            } else {
                status = "under_investigation";
                rationale = "컴포넌트 존재와 CVE candidate는 확인되었지만 사용 경로, 패치 여부, reachability 근거가 아직 부족해 추가 검토가 필요합니다.";
                if (observed != null) {
                    evidence.addAll(observed.evidence());
                }
            }

            if (needsAiRecommendation) {
                EvidenceInterpretation evidenceInterpretation = interpretEvidenceWithAi(ai, candidate, observed);
                ApplicabilityRecommendation recommendation = recommendApplicabilityWithAi(
                        ai,
                        candidate,
                        observed,
                        status,
                        rationale,
                        evidenceInterpretation
                );

                if (evidenceInterpretation.summary() != null && !evidenceInterpretation.summary().isBlank()) {
                    evidence.add("AI evidence summary: " + evidenceInterpretation.summary());
                }
                if (evidenceInterpretation.missingEvidence() != null && !evidenceInterpretation.missingEvidence().isEmpty()) {
                    evidence.add("AI missing evidence: " + String.join(", ", evidenceInterpretation.missingEvidence()));
                }
                if (recommendation.confidence() != null && !recommendation.confidence().isBlank()) {
                    evidence.add("AI recommendation confidence: " + recommendation.confidence());
                }
                if (recommendation.missingEvidence() != null && !recommendation.missingEvidence().isEmpty()) {
                    evidence.add("AI recommendation missing evidence: " + String.join(", ", recommendation.missingEvidence()));
                }
                if (recommendation.rationale() != null && !recommendation.rationale().isBlank()) {
                    rationale = recommendation.rationale();
                }
                if (isSupportedStatus(recommendation.recommendedStatus())) {
                    status = recommendation.recommendedStatus();
                }
            }

            assessments.add(new CveAssessment(
                    candidate.cveId(),
                    candidate.componentName(),
                    status,
                    rationale,
                    evidence,
                    candidate.matchedBy(),
                    "eeTeam",
                    LocalDate.now(SEOUL).toString()
            ));
        }

        assessments = assessments.stream()
                .sorted(Comparator.comparing(CveAssessment::status).thenComparing(CveAssessment::cveId))
                .toList();

        writeJson(assessmentPath, Map.of(
                "candidatePath", state.candidatePath(),
                "assessmentCount", assessments.size(),
                "assessments", assessments
        ));

        return new AssessmentState(
                state.request(),
                state.workspaceId(),
                state.observedComponents(),
                state.candidates(),
                assessments,
                assessmentPath.toString()
        );
    }

    @AchievesGoal(description = "Generate a verified CVE applicability report with evidence-backed status for customer delivery.")
    @Action(description = "Stage 4: Generate Markdown, JSON, and CSV report artifacts.")
    public CveReportResult generateCustomerReport(AssessmentState state, Ai ai) throws IOException {
        logger.info("CveReportAgent", "Generating customer-facing CVE report artifacts...");

        Path artifactDir = workspaceProvider.getSubPath(AGENT_NAME, state.workspaceId(), CoreWorkspaceProvider.SubCategory.ARTIFACTS);
        Path markdownPath = artifactDir.resolve("cve_report.md");
        Path csvPath = artifactDir.resolve("cve_report.csv");
        Path jsonPath = artifactDir.resolve("cve_report.json");

        String markdown = buildMarkdownReport(state, ai);
        String csv = buildCsv(state);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "productName", state.request().productName(),
                "productVersion", state.request().productVersion(),
                "inventoryPath", state.assessmentPath(),
                "observedComponents", state.observedComponents(),
                "assessmentPath", state.assessmentPath(),
                "assessments", state.assessments()
        ));

        writeTool.writeFile(markdownPath.toString(), markdown);
        writeTool.writeFile(csvPath.toString(), csv);
        writeTool.writeFile(jsonPath.toString(), json);

        long affectedCount = state.assessments().stream().filter(item -> "affected".equals(item.status())).count();
        long investigationCount = state.assessments().stream().filter(item -> "under_investigation".equals(item.status())).count();
        String summary = String.format(
                Locale.ROOT,
                "총 %d건 평가, affected %d건, under_investigation %d건",
                state.assessments().size(),
                affectedCount,
                investigationCount
        );

        return new CveReportResult(
                markdown,
                markdownPath.toString(),
                jsonPath.toString(),
                csvPath.toString(),
                summary,
                "SUCCESS"
        );
    }

    private JsonNode readJson(String path) throws IOException {
        return objectMapper.readTree(readTool.readFile(path).content());
    }

    private LicenseMapContext loadLicenseMapContext(String licenseMapPath, String buildDirectory, String sbomPath) throws IOException {
        JsonNode root = readJson(licenseMapPath);
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

    private void writeJson(Path path, Object payload) throws IOException {
        writeTool.writeFile(path.toString(), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
    }

    private record ComponentClassification(
            String sourceType,
            String confidence,
            boolean presentInProduct,
            List<String> evidence
    ) {}

    private ComponentClassification classifyComponent(
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

    private BuildEvidenceContext scanBuildEvidence(String buildDirectory) {
        if (buildDirectory == null || buildDirectory.isBlank()) {
            return new BuildEvidenceContext("", List.of());
        }

        Path buildPath = Paths.get(buildDirectory).normalize();
        if (!Files.exists(buildPath) || !Files.isDirectory(buildPath)) {
            logger.info("CveReportAgent", "Build directory not found or not a directory, skipping build evidence scan: " + buildDirectory);
            return new BuildEvidenceContext(buildDirectory, List.of());
        }

        List<String> indexedPaths = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(buildPath, 6)) {
            stream.filter(Files::isRegularFile)
                    .limit(5000)
                    .forEach(path -> indexedPaths.add(buildPath.relativize(path).toString().replace('\\', '/').toLowerCase(Locale.ROOT)));
        } catch (IOException e) {
            logger.info("CveReportAgent", "Build evidence scan failed: " + e.getMessage());
        }

        logger.info("CveReportAgent", "Indexed build artifacts: " + indexedPaths.size());
        return new BuildEvidenceContext(buildDirectory, indexedPaths);
    }

    private List<String> findBuildEvidence(BuildEvidenceContext context, String normalizedName, String originalName, String purl) {
        if (context.indexedPaths().isEmpty()) {
            return List.of();
        }

        Set<String> aliases = componentAliases(normalizedName, originalName, purl);
        List<String> matches = new ArrayList<>();
        for (String indexedPath : context.indexedPaths()) {
            for (String alias : aliases) {
                if (alias.isBlank()) {
                    continue;
                }
                if (indexedPath.contains(alias)) {
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

    private Set<String> componentAliases(String normalizedName, String originalName, String purl) {
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

    private boolean looksSyntheticFromLicenseMap(JsonNode componentNode) {
        return isEmptyNode(componentNode.get("properties"))
                && isEmptyNode(componentNode.get("evidence"))
                && isEmptyNode(componentNode.get("externalReferences"))
                && isEmptyNode(componentNode.get("hashes"))
                && isBlankNode(componentNode.get("cpe"))
                && isBlankNode(componentNode.get("description"));
    }

    private boolean matchesLicenseMapMetadata(JsonNode licenseInfo, String version, String purl) {
        if (licenseInfo == null || licenseInfo.isMissingNode() || licenseInfo.isNull()) {
            return false;
        }
        String expectedVersion = textOrDefault(licenseInfo, "version", "");
        String expectedPurl = textOrDefault(licenseInfo, "purl", "");
        boolean versionMatches = expectedVersion.isBlank() || Objects.equals(expectedVersion, version);
        boolean purlMatches = expectedPurl.isBlank() || Objects.equals(expectedPurl, purl);
        return versionMatches && purlMatches;
    }

    private boolean hasStrongSbomEvidence(JsonNode componentNode) {
        return !isEmptyNode(componentNode.get("properties"))
                || !isEmptyNode(componentNode.get("evidence"))
                || !isEmptyNode(componentNode.get("externalReferences"))
                || !isEmptyNode(componentNode.get("hashes"))
                || !isBlankNode(componentNode.get("cpe"));
    }

    private boolean isEmptyNode(JsonNode node) {
        return node == null || node.isNull() || (node.isArray() && node.isEmpty()) || (node.isObject() && node.isEmpty());
    }

    private boolean isBlankNode(JsonNode node) {
        return node == null || node.isNull() || node.asText("").isBlank();
    }

    private ObservedComponent preferHigherConfidenceComponent(ObservedComponent left, ObservedComponent right) {
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

    private int confidenceScore(ObservedComponent component) {
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

    private List<JsonNode> iterable(JsonNode node) {
        List<JsonNode> items = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return items;
        }
        node.forEach(items::add);
        return items;
    }

    private String textOrDefault(JsonNode node, String fieldName, String defaultValue) {
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

    private List<String> collectReferences(JsonNode vulnNode) {
        Set<String> refs = new LinkedHashSet<>();
        for (JsonNode referenceNode : iterable(vulnNode.path("references"))) {
            String url = textOrDefault(referenceNode, "url", "");
            if (!url.isBlank()) {
                refs.add(url);
            }
        }
        return List.copyOf(refs);
    }

    private List<String> collectAllReferences(JsonNode vulnNode) {
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

    private List<String> collectAdvisoryValues(JsonNode vulnNode, String fieldName) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode advisoryNode : iterable(vulnNode.path("advisories"))) {
            String value = textOrDefault(advisoryNode, fieldName, "");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private String firstRatingValue(JsonNode vulnNode, String fieldName) {
        JsonNode ratings = vulnNode.path("ratings");
        if (!ratings.isArray() || ratings.isEmpty()) {
            return "UNKNOWN";
        }
        return textOrDefault(ratings.get(0), fieldName, "UNKNOWN");
    }

    private String firstRatingScore(JsonNode vulnNode) {
        JsonNode ratings = vulnNode.path("ratings");
        if (!ratings.isArray() || ratings.isEmpty()) {
            return "";
        }
        JsonNode scoreNode = ratings.get(0).get("score");
        return scoreNode == null || scoreNode.isNull() ? "" : scoreNode.asText("");
    }

    private Set<String> parseTaggedCves(String analystNotes, String tag) {
        if (analystNotes == null || analystNotes.isBlank()) {
            return Set.of();
        }
        String prefix = tag.toLowerCase(Locale.ROOT) + ":";
        Set<String> result = new LinkedHashSet<>();
        for (String line : analystNotes.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            String value = trimmed.substring(prefix.length()).trim();
            if (value.isBlank()) {
                continue;
            }
            for (String token : value.split(",")) {
                String normalized = token.trim();
                if (!normalized.isBlank()) {
                    result.add(normalized);
                }
            }
        }
        return result;
    }

    private EvidenceInterpretation interpretEvidenceWithAi(Ai ai, CveCandidate candidate, ObservedComponent observed) {
        try {
            String prompt = promptProvider.getPrompt("agents/cvereport/interpret-evidence.jinja", Map.of(
                    "candidate", candidate,
                    "observed", observed
            ));
            return ai.withLlm(LlmOptions.withLlmForRole("normal").withoutThinking())
                    .creating(EvidenceInterpretation.class)
                    .fromPrompt(prompt);
        } catch (Exception e) {
            logger.info("CveReportAgent", "AI evidence interpretation failed, using fallback: " + e.getMessage());
            return new EvidenceInterpretation(
                    "Local evidence confirms component presence but not complete reachability of the vulnerable feature.",
                    "medium",
                    List.of("feature usage grep", "config review", "build flag confirmation")
            );
        }
    }

    private ApplicabilityRecommendation recommendApplicabilityWithAi(
            Ai ai,
            CveCandidate candidate,
            ObservedComponent observed,
            String currentStatus,
            String currentRationale,
            EvidenceInterpretation evidenceInterpretation
    ) {
        try {
            String prompt = promptProvider.getPrompt("agents/cvereport/assess-applicability.jinja", Map.of(
                    "candidate", candidate,
                    "observed", observed,
                    "currentStatus", currentStatus,
                    "currentRationale", currentRationale,
                    "evidenceInterpretation", evidenceInterpretation
            ));
            return ai.withLlm(LlmOptions.withLlmForRole("normal").withoutThinking())
                    .creating(ApplicabilityRecommendation.class)
                    .fromPrompt(prompt);
        } catch (Exception e) {
            logger.info("CveReportAgent", "AI applicability recommendation failed, using fallback: " + e.getMessage());
            return new ApplicabilityRecommendation(
                    currentStatus,
                    currentRationale,
                    "medium",
                    List.of("feature reachability confirmation")
            );
        }
    }

    private boolean isSupportedStatus(String status) {
        return Set.of(
                "affected",
                "not_affected_not_present",
                "not_affected_unused_code_path",
                "not_affected_patched",
                "fixed",
                "under_investigation"
        ).contains(status);
    }

    private String buildMarkdownReport(AssessmentState state, Ai ai) {
        try {
            String prompt = promptProvider.getPrompt("agents/cvereport/generate-report.jinja", Map.of(
                    "productName", state.request().productName(),
                    "productVersion", state.request().productVersion(),
                    "assessmentDate", LocalDate.now(SEOUL).toString(),
                    "assessments", state.assessments()
            ));
            return ai.withLlm(LlmOptions.withLlmForRole("performant").withoutThinking().withMaxTokens(65536))
                    .generateText(prompt);
        } catch (Exception e) {
            logger.info("CveReportAgent", "AI report generation failed, falling back to deterministic markdown: " + e.getMessage());
            return buildDeterministicMarkdownReport(state);
        }
    }

    private String buildDeterministicMarkdownReport(AssessmentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("# CVE Assessment Report\n\n");
        sb.append("- Product: ").append(state.request().productName()).append("\n");
        sb.append("- Version: ").append(state.request().productVersion()).append("\n");
        sb.append("- Review Date: ").append(LocalDate.now(SEOUL)).append("\n");
        sb.append("- Inventory Path: ").append(state.observedComponents().size()).append(" observed components\n\n");
        sb.append("## Summary\n\n");
        sb.append("- Total observed components: ").append(state.observedComponents().size()).append("\n");
        sb.append("- Total CVE candidates: ").append(state.candidates().size()).append("\n");
        sb.append("- Total assessments: ").append(state.assessments().size()).append("\n\n");
        sb.append("## Findings\n\n");
        sb.append("| Component | CVE | Status | Rationale | Owner | Review Date |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        for (CveAssessment assessment : state.assessments()) {
            sb.append("| ")
                    .append(escapeCell(assessment.componentName())).append(" | ")
                    .append(escapeCell(assessment.cveId())).append(" | ")
                    .append(escapeCell(assessment.status())).append(" | ")
                    .append(escapeCell(assessment.rationale())).append(" | ")
                    .append(escapeCell(assessment.owner())).append(" | ")
                    .append(escapeCell(assessment.reviewDate())).append(" |\n");
        }
        return sb.toString();
    }

    private String buildCsv(AssessmentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Component,Version,CVE,Status,Rationale,Evidence,Source,Owner,Review Date\n");
        Map<String, String> versions = state.observedComponents().stream()
                .collect(Collectors.toMap(
                        component -> normalize(component.componentName()),
                        ObservedComponent::version,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        for (CveAssessment assessment : state.assessments()) {
            String version = versions.getOrDefault(normalize(assessment.componentName()), "");
            sb.append(csvCell(assessment.componentName())).append(",")
                    .append(csvCell(version)).append(",")
                    .append(csvCell(assessment.cveId())).append(",")
                    .append(csvCell(assessment.status())).append(",")
                    .append(csvCell(assessment.rationale())).append(",")
                    .append(csvCell(String.join(" ; ", assessment.evidence()))).append(",")
                    .append(csvCell(String.join(" ; ", assessment.source()))).append(",")
                    .append(csvCell(assessment.owner())).append(",")
                    .append(csvCell(assessment.reviewDate())).append("\n");
        }
        return sb.toString();
    }

    private String escapeCell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", "<br/>");
    }

    private String csvCell(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private List<Path> findSupplementalGrypeFiles(String sbomPath) {
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

    private String normalizeRef(String ref) {
        return stripPackageIdSuffix(ref).toLowerCase(Locale.ROOT);
    }

    private String stripPackageIdSuffix(String ref) {
        int idx = ref.indexOf("?package-id=");
        return idx >= 0 ? ref.substring(0, idx) : ref;
    }

    private String nameVersionKeyFromPurl(String ref) {
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

    private String preferNonUnknown(String left, String right) {
        if (left == null || left.isBlank() || "UNKNOWN".equalsIgnoreCase(left)) {
            return right;
        }
        return left;
    }

    private String preferLongerValue(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        if (right != null && right.length() > left.length()) {
            return right;
        }
        return left;
    }

    private String mergeConfidence(String left, String right) {
        List<String> order = List.of("low", "medium", "high");
        int leftIdx = order.indexOf(left);
        int rightIdx = order.indexOf(right);
        return order.get(Math.max(leftIdx, rightIdx));
    }

    private String detectToolchain(String buildDirectory, String sbomPath) {
        String joined = ((buildDirectory == null ? "" : buildDirectory) + " " + (sbomPath == null ? "" : sbomPath)).toLowerCase(Locale.ROOT);
        if (joined.contains("autotalks")) {
            return "autotalks";
        }
        if (joined.contains("hed")) {
            return "hed";
        }
        return "default";
    }

    private String stripSrcSuffix(String value) {
        return value.endsWith("src") ? value.substring(0, value.length() - 3) : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
    }
}
