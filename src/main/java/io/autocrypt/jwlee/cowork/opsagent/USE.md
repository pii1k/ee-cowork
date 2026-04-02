# OpsAgent Usage Guide

The `OpsAgent` is designed to analyze a repository's infrastructure and operations. It determines whether a project is built for Cloud-native, Appliance, or On-prem deployment and evaluates its HA (High Availability) and IaC status.

## CLI Command

You can use the `ops-analyze` command in the Spring Shell.

### Parameters
- `path`: (Optional) The root path of the project to analyze. Defaults to the current directory (`.`).
- `context`: (Optional) Additional context or specific instructions for the analysis.
- `--show-prompts`: (Optional) Set to `true` to see the prompts sent to the LLM.
- `--show-responses`: (Optional) Set to `true` to see the raw responses from the LLM.

### Example
```bash
shell:> ops-analyze --path ./pnc-project --context "Check if this is ready for high-availability cloud deployment."
```

## How it Works
1. **Stage 0 (Context Priming)**: The agent invokes `ArchitectureAgent` to understand the overall tech stack and modules.
2. **Stage 1 (Discovery)**: It scans the repository for `Dockerfile`, `*.yaml`, `*.tf`, `Jenkinsfile`, and operational keywords (`pkcs11`, `udev`, `Redis`, `health`, `vault`, etc.).
3. **Stage 2 (Analysis)**: The LLM analyzes the discovered evidence to categorize the deployment model and evaluate reliability and security.
4. **Stage 3 (Synthesis)**: All findings are synthesized into a comprehensive Markdown report in Korean, including a **Gap Analysis** and **Improvement Recommendations**.

## Output
The command returns a detailed Markdown report containing:
- Operational Summary (Deployment Type)
- Reliability & HA Diagnostic
- IaC Maturity Assessment
- Security & Observability Review
- **Gap Analysis & Recommendations**
