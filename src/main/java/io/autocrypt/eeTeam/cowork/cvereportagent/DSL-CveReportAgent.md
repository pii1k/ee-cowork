# DSL-CveReportAgent

> [IMPORTANT] When implementing this agent, you MUST refer to the following guides for ground truth on coding patterns, workarounds, and framework usage:
> - `guides/DSL_GUIDE.md`: Standard DSL rules and common pitfalls.
> - `guides/few-shots/embabel-few-shot.md`: Verified Embabel coding patterns and DTO structures.
> - `guides/few-shots/spring-shell-few-shot.md`: Verified CLI command implementation patterns.
> - `sbom/cve_report_rules.md`: Product-specific CVE reporting workflow and decision rules.
> - `sbom/postprocess_sbom.py`: Existing SBOM post-processing behavior, especially component filtering and `license_map` usage.
> - `sbom/generate_sbom.sh`: Existing SBOM generation flow and output conventions.

## 1. Metadata

```yaml
agent:
  name: CveReportAgent
  description: "Collects observed product components, gathers CVE candidates, assesses applicability, and generates customer-facing CVE reports with evidence."
  timezone: "Asia/Seoul"
  language: "Korean"
  workspace: "cve-report"
  java_package_base: "io.autocrypt.eeTeam.cowork"
```

## 2. Dependencies (Constructor Injection)

The following Spring beans and tools must be injected via the constructor. Do not pass them into `@Action` method signatures.

- `PromptProvider` (REQUIRED for Jinja prompt resolution)
- `FileReadTool` (REQUIRED for loading SBOM JSON, rules, and optional analyst inputs)
- `FileWriteTool` (REQUIRED for writing intermediate JSON and final reports)
- `CoreWorkspaceProvider` (REQUIRED for standardized output paths)
- `CoworkLogger` (REQUIRED for step logging)
- `LocalRagTools` (OPTIONAL but recommended for storing rule/evidence memory per report workspace)
- `BashTool` (OPTIONAL for invoking shell-based evidence collection such as `rg`, `readelf`, `ldd`, or existing SBOM helper scripts)

## 3. Domain Objects (DTOs)

Defined as `public record` within the agent class.

```yaml
CveReportRequest:
  sbomPath: String # Path to CycloneDX SBOM JSON, e.g. sbom/output/acv2x_ee_sbom.json
  productName: String # User-facing product name
  productVersion: String # Product release/build version
  buildDirectory: String # Optional build output directory for evidence collection
  licenseMapPath: String # Usually sbom/license_map.json
  outputFormat: String # markdown | json | csv | all
  analystNotes: String # Optional human notes, known patches, exclusions, review hints

ObservedComponent:
  componentName: String
  version: String
  purl: String
  sourceType: String # sbom | binary_scan | manual_map
  confidence: String # high | medium | low
  presentInProduct: boolean
  evidence: List<String>

ObservedComponentList:
  components: List<ObservedComponent>

CveCandidate:
  cveId: String
  componentName: String
  componentVersion: String
  matchedBy: List<String> # sbom | grype | osv | nvd_version_match | vendor_advisory | manual_rule
  severity: String
  cvss: String
  fixedVersions: List<String>
  referenceUrls: List<String>
  matchConfidence: String # high | medium | low

CveCandidateList:
  candidates: List<CveCandidate>

CveAssessment:
  cveId: String
  componentName: String
  status: String # affected | not_affected_not_present | not_affected_unused_code_path | not_affected_patched | fixed | under_investigation
  rationale: String
  evidence: List<String>
  source: List<String>
  owner: String
  reviewDate: String # YYYY-MM-DD

CveAssessmentList:
  assessments: List<CveAssessment>

FinalCveReport:
  markdownReport: String
  jsonReportPath: String
  csvReportPath: String
  markdownReportPath: String
  summary: String
```

## 4. Workflow States (`@State`)

```yaml
State: InventoryState {
  request: CveReportRequest
  workspaceId: String
  observedComponents: List<ObservedComponent>
  inventoryPath: String
}

State: CandidateCollectionState {
  request: CveReportRequest
  workspaceId: String
  observedComponents: List<ObservedComponent>
  inventoryPath: String
  candidates: List<CveCandidate>
  candidatePath: String
}

State: AssessmentState {
  request: CveReportRequest
  workspaceId: String
  observedComponents: List<ObservedComponent>
  candidates: List<CveCandidate>
  assessments: List<CveAssessment>
  assessmentPath: String
}
```

## 5. Actions (`@Action`)

### Action 1: `buildObservedInventory`
- **Goal**: Confirm which components are actually observed in the product.
- **Input**: `CveReportRequest`
- **Output**: `InventoryState`
- **LLM Configuration**: None for the core extraction logic. Prefer deterministic Java parsing first.
- **Logic**:
  1. Read the input SBOM JSON and extract components.
  2. Distinguish actual SBOM-observed components from libraries injected by `license_map` or post-processing helper logic.
  3. Optionally enrich evidence using build directory scans, `readelf`, `ldd`, or `rg` when `buildDirectory` is provided.
  4. Save `inventory.json` into the agent workspace.
  5. Return `InventoryState`.

### Action 2: `collectCveCandidates`
- **Goal**: Gather broad CVE candidates for each observed component/version pair.
- **Input**: `InventoryState`
- **Output**: `CandidateCollectionState`
- **LLM Configuration**: None for raw data collection. Use Java logic and tool output first.
- **Logic**:
  1. Read SBOM `vulnerabilities` as candidate sources only.
  2. Optionally parse grype or other raw scan outputs if present under known paths.
  3. Merge candidates per component and CVE.
  4. Preserve provenance in `matchedBy` and `referenceUrls`.
  5. Save `cve_candidates.json`.

### Action 3: `assessApplicability`
- **Goal**: Apply product-specific rules to classify each CVE.
- **Input**: `CandidateCollectionState`
- **Output**: `AssessmentState`
- **LLM Configuration**:
  - `role`: `normal`
  - `temperature`: `0.1`
  - `template`: `agents/cvereport/assess-applicability.jinja`
- **Logic**:
  1. Start from deterministic rules:
     - observed component missing -> `not_affected_not_present`
     - explicit patch/backport evidence -> `not_affected_patched`
     - fixed version proven in product -> `fixed`
  2. Use LLM only for rationale synthesis and ambiguous evidence interpretation, not for raw fact extraction.
  3. Preserve all evidence lines used to justify the status.
  4. Route uncertain cases to `under_investigation` instead of over-claiming `affected` or `not_affected`.
  5. Save `cve_assessment.json`.

### Action 4: `generateCustomerReport`
- **Goal**: Produce the final customer-facing report in Korean.
- **Input**: `AssessmentState`
- **Output**: `FinalCveReport`
- **LLM Configuration**:
  - `role`: `performant`
  - `temperature`: `0.2`
  - `template`: `agents/cvereport/generate-report.jinja`
- **Logic**:
  1. Convert assessments into a normalized table with columns:
     `Component`, `Version`, `CVE`, `Severity`, `CVSS`, `Fixed Version`, `Status`, `Rationale`, `Evidence`, `Source`, `Owner`, `Review Date`.
  2. Generate a Korean Markdown summary for external delivery.
  3. Persist Markdown, JSON, and CSV outputs in the workspace artifact directory.

### Action 5: `finalizeCveReport`
- **Goal**: `@AchievesGoal(description = "Generate a verified CVE applicability report with evidence-backed status for customer delivery.")`
- **Input**: `FinalCveReport`
- **Output**: `FinalCveReport`
- **LLM Configuration**: None.
- **Logic**:
  1. Return the final report object as the agent goal result.
  2. Ensure output paths are already written before returning.

## 6. Mandatory Implementation Guidelines

### 6.1 Source-of-Truth Rules
- `license_map.json` is an enrichment source for identification, metadata, and dependency hints. It MUST NOT be treated as proof that a component is present in the product.
- SBOM `vulnerabilities` fields are candidate inputs only. They MUST NOT become final affected verdicts without applicability assessment.
- If post-processing injects missing core libraries, implementation MUST mark them as non-observed unless additional product evidence exists.

### 6.2 Assessment Policy
- Prefer explicit, auditable statuses over binary vulnerable/not-vulnerable outcomes.
- `under_investigation` is the safe default when evidence is insufficient.
- Every assessment row MUST include evidence and rationale.
- Final status rules should directly reflect `sbom/cve_report_rules.md`.

### 6.3 Hybrid Logic
- JSON parsing, file discovery, deduplication, rule matching, and report serialization should be Java logic.
- Use the LLM for summarization, rationale drafting, and interpretation of ambiguous evidence only.
- Do not ask the LLM to invent missing evidence.

### 6.4 Storage
- Use `CoreWorkspaceProvider.getSubPath("cvereportagent", workspaceId, SubCategory.ARTIFACTS)` for report files.
- Save intermediate machine-readable outputs:
  - `inventory.json`
  - `cve_candidates.json`
  - `cve_assessment.json`
  - `cve_report.md`
  - `cve_report.csv`
- Use `workspaceProvider.toSlug(productName + "-" + productVersion)` to derive `workspaceId`.

### 6.5 Tooling and Model Safety
- If the implementation uses `BashTool`, keep commands read-oriented and deterministic.
- If Gemini 2.5+ models are used with tools or RAG, call `.withoutThinking()`.
- Avoid large raw SBOM dumps in prompts. Pass only the normalized DTO/state content required for the current stage.

### 6.6 CLI Command
- Implementation MUST include `CveReportCommand` extending `BaseAgentCommand`.
- The command MUST support `-p` (`--show-prompts`) and `-r` (`--show-responses`) flags.
- Recommended CLI inputs:
  - `--sbom`
  - `--product`
  - `--version`
  - `--build-dir`
  - `--license-map`
  - `--format`
  - `--notes`

### 6.7 Package and Placement
- New Java logic for this agent MUST use the `eeTeam` namespace, not the `jwlee` namespace.
- Recommended package path for implementation:
  - `io.autocrypt.eeTeam.cowork.cvereportagent`
- Recommended relocation path for the DSL before implementation:
  - `src/main/java/io/autocrypt/eeTeam/cowork/cvereportagent/DSL-CveReportAgent.md`
- If the runtime application currently scans only `io.autocrypt.jwlee.cowork`, the implementation step MUST also update component scanning and any related Modulith/application bootstrap settings so the `eeTeam` package is discovered.

### 6.8 MVP Scope
- MVP may start with local files only:
  - CycloneDX SBOM JSON
  - `license_map.json`
  - optional grype raw results
  - optional analyst notes
- External advisory connectors can be added later, but the DTOs and states should already preserve source provenance to support them.
