# DSL-ApiAgent.md

## 0. Header
This DSL defines the `ApiAgent`, which is responsible for discovering and documenting all API endpoints within a codebase. It follows the Embabel Agent DSL Guide (v1.0).

## 1. Metadata
```yaml
agent:
  name: ApiAgent
  description: "Discovers and analyzes all API endpoints in the codebase (REST, GraphQL, etc.)"
  timezone: "Asia/Seoul" # REQUIRED
  language: "Korean" # REQUIRED: Use Korean for all user-facing outputs and reports.
```

## 2. Dependencies
- `PromptProvider` (REQUIRED for Jinja)
- `FileReadTool`
- `GrepTool`
- `GlobTool`
- `CoworkLogger`
- `AgentPlatform` (For cross-agent communication)

## 3. Domain Objects (DTOs)

### ApiRequest
- `path`: String # Root path of the project to analyze
- `context`: String # Additional context (e.g., specific modules to focus on)

### EndpointInfo
- `method`: String # HTTP Method (GET, POST, etc.)
- `path`: String # Full endpoint path
- `parameters`: List<ParameterInfo> # Request parameters/body
- `returnType`: String # Response type/format
- `description`: String # Summary of what the endpoint does
- `sourceFile`: String # File where it's defined

### ParameterInfo
- `name`: String
- `type`: String
- `required`: boolean
- `description`: String

### ControllerBatch
- `controllerName`: String
- `basePath`: String
- `endpoints`: List<EndpointInfo>

### ExtractedApiBatch
- `controllers`: List<ControllerBatch>

### FinalApiResponse
- `report`: String # Markdown formatted API documentation
- `status`: String

## 4. Workflow States

### State: ContextPrimingState
- `request`: ApiRequest

### State: ApiDiscoveryState
- `request`: ApiRequest
- `controllerFiles`: List<String> # List of files suspected to contain API controllers
- `techStack`: String # Detected framework (e.g., Spring Boot, FastAPI)

### State: ApiExtractionState
- `request`: ApiRequest
- `batches`: List<ExtractedApiBatch>

## 5. Actions

### Action: prepareContext (Stage 0)
- **Goal**: Prime the analysis context using `ArchitectureAgent` if the initial context is insufficient.
- **Input**: `ApiRequest`
- **Output**: `ApiDiscoveryState` (via `discoverControllers`)
- **Logic**:
  1. Check if `ApiRequest.context` is substantial.
  2. If not, invoke `ArchitectureAgent` using `AgentInvocation` to get a structural overview.
  3. Append the architecture summary, tech stack, and module list to the context.
  4. Proceed to `discoverControllers` with the primed context.

### Action: discoverControllers (Stage 1)
- **Goal**: Identify all controller files and the underlying technology stack.
- **Input**: `ApiRequest` or `ContextPrimingState`
- **Output**: `ApiDiscoveryState`
- **Logic**:
  1. Use `GrepTool` to search for framework-specific annotations (e.g., `@RestController`, `@Controller`, `@Path`, `@app.get`).
  2. Use `FileReadTool` on entry points (pom.xml, package.json, main.py) to confirm the tech stack.
- **LLM Configuration**:
  - `role`: normal
  - `template`: `agents/api/discover-controllers.jinja`

### Action: extractEndpoints (Map Phase)
- **Goal**: Parse batches of controller files to extract detailed endpoint information.
- **Input**: `ApiDiscoveryState`
- **Output**: `ApiExtractionState`
- **Logic**:
  1. Split `controllerFiles` into chunks (e.g., 5-10 files per batch).
  2. For each chunk, use `Ai` to extract `ExtractedApiBatch`.
  3. Parallel execution is recommended for large projects.
- **LLM Configuration**:
  - `role`: performant
  - `temperature`: 0.1
  - `template`: `agents/api/extract-endpoints-batch.jinja`

### Action: synthesizeApiReport (Reduce Phase)
- **AchievesGoal**: Generate the final Markdown documentation.
- **Input**: `ApiExtractionState`
- **Output**: `FinalApiResponse`
- **Logic**:
  1. Merge all `ExtractedApiBatch` into a single structured representation.
  2. Format into a clean, professional Markdown report in **Korean**.
  3. Include sections for each controller, sorted by path.
- **LLM Configuration**:
  - `role`: performant
  - `template`: `agents/api/synthesize-report.jinja`

## 6. Implementation Guidelines

### 6.1 Tool Usage
- Use `GrepTool` extensively in the discovery phase to minimize `FileReadTool` calls.
- In the extraction phase, provide the full content of controller files to the LLM to ensure accuracy in parameter and return type detection.

### 6.2 Tech Stack Sensitivity
- The agent MUST support at least **Spring Boot** (Java/Kotlin) and should be extensible for **FastAPI/Flask** (Python) and **Express** (Node.js).
- Use `ArchitectureAgent` for initial priming if `ApiRequest.context` is insufficient, similar to `ErdAgent.prepareContext`.

### 6.3 Output Requirements
- The final report MUST be in **Korean**.
- Use Mermaid diagrams if applicable (e.g., for complex flow or API dependencies).
- Ensure all paths are normalized (e.g., combining `@RequestMapping` base path with method-level `@GetMapping` path).

### 6.4 Pitfall Prevention
- **Batching**: Large codebases must be processed in batches to avoid token limits.
- **Thinking**: Call `.withoutThinking()` for extraction actions to avoid tool-call latency if using Gemini 2.0+ models.
- **Validation**: Ensure `EndpointInfo` fields are non-null using `@NotBlank` where appropriate.
