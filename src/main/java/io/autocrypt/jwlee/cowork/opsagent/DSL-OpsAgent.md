# DSL-OpsAgent.md

## 0. Header
This DSL defines the `OpsAgent`, which analyzes the repository to determine its intended operational environment (Appliance, Cloud, On-prem), HA configuration, IaC maturity, and security posture. It follows the Embabel Agent DSL Guide (v1.0).

## 1. Metadata
```yaml
agent:
  name: OpsAgent
  description: "Analyzes repository infrastructure, operational mode, HA, and IaC status."
  timezone: "Asia/Seoul" # REQUIRED
  language: "Korean" # REQUIRED: Use Korean for all user-facing outputs and reports.
```

## 2. Dependencies
- `PromptProvider` (REQUIRED for Jinja)
- `FileReadTool`
- `GrepTool`
- `GlobTool`
- `CoworkLogger`
- `AgentPlatform` (For ArchitectureAgent priming)

## 3. Domain Objects (DTOs)

### OpsRequest
- `path`: String # Root path of the project to analyze
- `context`: String # Additional context

### InfrastructureEvidence
- `category`: String # Deployment, HA, IaC, Security, Observability
- `fileName`: String
- `snippet`: String # Relevant part of the file
- `reasoning`: String # Why this is relevant

### DeploymentAnalysis
- `type`: String # APPLIANCE | CLOUD_NATIVE | ON_PREM_VM | HYBRID
- `confidence`: double # 0.0 to 1.0
- `evidenceSummary`: String
- `platformDetails`: String # (e.g., AWS, GCP, Azure, Bare-metal)

### ReliabilityAnalysis
- `haEnabled`: boolean
- `sessionSharing`: String # (e.g., Redis, Sticky Session, Stateless JWT)
- `databaseRedundancy`: String # (e.g., Read/Write Splitting, Cluster)
- `scalabilityNotes`: String

### SecurityObservabilityAnalysis
- `secretsManagement`: String # (e.g., Vault, Environment, Hardcoded)
- `hsmIntegration`: boolean # (e.g., PKCS#11, AWS CloudHSM)
- `monitoringTools`: List<String> # (e.g., Prometheus, ELK)
- `securityGaps`: List<String>

### FinalOpsReport
- `report`: String # Markdown formatted report in Korean
- `status`: String

## 4. Workflow States

### State: OpsPrimingState
- `request`: OpsRequest
- `archContext`: String # Summary from ArchitectureAgent

### State: OpsDiscoveryState
- `request`: OpsRequest
- `evidenceList`: List<InfrastructureEvidence>
- `techStack`: String

### State: OpsCategorizationState
- `request`: OpsRequest
- `deployment`: DeploymentAnalysis
- `reliability`: ReliabilityAnalysis
- `securityObs`: SecurityObservabilityAnalysis

## 5. Actions

### Action: prepareOpsContext (Stage 0)
- **Goal**: Prime context using `ArchitectureAgent`.
- **Input**: `OpsRequest`
- **Output**: `OpsPrimingState`
- **Logic**: Invoke `ArchitectureAgent` to get a high-level view of the project's structure and technology stack.

### Action: discoverInfrastructureFiles (Stage 1)
- **Goal**: Scan the repository for operational artifacts.
- **Input**: `OpsPrimingState`
- **Output**: `OpsDiscoveryState`
- **Logic**:
  1. Use `GlobTool` to find `Dockerfile`, `*.yaml` (k8s/helm), `*.tf`, `Jenkinsfile`, `.github/workflows/*`.
  2. Use `GrepTool` for appliance-specific keywords (`pkcs11`, `udev`, `systemd`, `sysctl`, `HSM`).
  3. Use `GrepTool` for HA/Reliability patterns (`Redis`, `DataSource`, `health`, `lb`).
  4. Collect snippets and categorize them into `InfrastructureEvidence`.

### Action: analyzeOpsLayers (Stage 2 - Map Phase)
- **Goal**: Deeply analyze the gathered evidence.
- **Input**: `OpsDiscoveryState`
- **Output**: `OpsCategorizationState`
- **Logic**:
  1. Parallel analysis of Deployment type.
  2. Parallel analysis of Reliability/HA.
  3. Parallel analysis of Security/Observability.
- **LLM Configuration**:
  - `role`: performant
  - `template`: `agents/ops/analyze-layers.jinja`

### Action: synthesizeOpsReport (Stage 3 - Reduce Phase)
- **AchievesGoal**: Generate the final comprehensive report.
- **Input**: `OpsCategorizationState`
- **Output**: `FinalOpsReport`
- **Logic**: Synthesize all findings, highlight gaps, and provide actionable recommendations in **Korean**.
- **LLM Configuration**:
  - `role`: performant
  - `template`: `agents/ops/synthesize-report.jinja`

## 6. Implementation Guidelines

### 6.1 Evidence Categories
- **Appliance**: Look for specialized OS configurations, fixed IP settings, HSM integration, and low-level hardware control.
- **Cloud Native**: Look for K8s manifests, Helm charts, Terraform/HCL, and Cloud-specific SDKs.
- **HA**: Search for distributed caching, database clustering configurations, and load balancer health checks.

### 6.2 Gap Reporting
- Automatically flag missing critical components (e.g., "Deployment is Cloud Native but no IaC found").
- Provide specific file path suggestions for where to add missing configurations.

### 6.3 Performance
- Use `parallelStream` for Stage 2 analysis if the evidence list is long.
- Call `.withoutThinking()` for Stage 2 tasks if using Gemini 2.0+ models.
