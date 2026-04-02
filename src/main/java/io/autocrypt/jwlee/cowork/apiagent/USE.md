# ApiAgent Usage Guide

The `ApiAgent` is designed to automatically discover and document API endpoints in a codebase. It supports various frameworks and languages by using a multi-stage analysis process.

## CLI Command

You can use the `api-analyze` command in the Spring Shell.

### Parameters
- `path`: (Optional) The root path of the project to analyze. Defaults to the current directory (`.`).
- `context`: (Optional) Additional context or specific instructions for the analysis.
- `--show-prompts`: (Optional) Set to `true` to see the prompts sent to the LLM.
- `--show-responses`: (Optional) Set to `true` to see the raw responses from the LLM.

### Example
```bash
shell:> api-analyze --path ./my-project --context "Focus on the order management module."
```

## How it Works
1. **Stage 0 (Context Priming)**: If the provided context is short, the agent invokes the `ArchitectureAgent` to gain a structural understanding of the project.
2. **Stage 1 (Discovery)**: The agent searches for controller/router files using framework-specific patterns (Spring Boot, FastAPI, Express, etc.).
3. **Stage 2 (Extraction)**: Controller files are parsed in batches by the LLM to extract detailed endpoint information (Methods, Paths, Parameters, Return Types).
4. **Stage 3 (Synthesis)**: The extracted information is synthesized into a comprehensive Markdown report in Korean.

## Output
The command returns a detailed Markdown report containing:
- API Overview
- Detailed endpoint documentation grouped by controller
- Parameter and return type details
- Recommendations for API design (if applicable)
