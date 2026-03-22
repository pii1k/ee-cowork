# Presales Engineering (SE) Analysis Agent Plan

## 1. Goal
Create a specialized agent for Presales/Solution Architects to analyze customer requirements, refine them into technical specifications (CRS), and perform gap analysis against product capabilities.

## 2. Core Concepts
- **Domain Specialization**: Uses two separate RAG indices to prevent context mixing:
    - `tech-ref`: Industry standards (e.g., IEEE 1609.2, V2X) for requirement refinement.
    - `product-spec`: Internal product specifications and roadmaps for gap analysis.
- **File-Based Workflow**: Instead of internal HITL states, the agent persists intermediate results to Markdown files, allowing users to manually edit them before moving to the next stage.
- **Resumption**: A dedicated command picks up from the saved state and modified files.

## 3. Workflow Structure

### Mode A: Full Execution (`presales-start`)
1. **Input**: Customer email text or file.
2. **Process**:
    - **Phase 1 (Refinement)**: Query `tech-ref` RAG and generate `crs.md`.
    - **Phase 2 (Analysis)**: Immediately read the generated `crs.md`, query `product-spec` RAG, and generate `analysis.md`, `questions.md`, and `final_report.md`.
3. **Output**: All files are saved to the workspace. The user receives a notification that the entire analysis is complete.

### Mode B: Optional Resume (`presales-resume`)
1. **Trigger**: User is unsatisfied with the results and manually edits `crs.md`.
2. **Process**:
    - The agent skips Phase 1.
    - It reads the **manually edited `crs.md`** from the workspace.
    - It re-runs **Phase 2 (Analysis)** to update `analysis.md`, `questions.md`, and `final_report.md` based on the new requirements.
3. **Output**: Updated analysis and final report files.

## 4. Implementation Details

### Package: `io.autocrypt.jwlee.cowork.agents.presales`

- **PresalesWorkspace**: Manages directory structure in `output/presales/`, handles state persistence (`state.json`), and provides file I/O for `crs.md`, `analysis.md`, etc.
- **PresalesAgent**: Contains the LLM logic using Embabel's `Ai` interface. Defines prompts for CRS generation and Gap Analysis.
- **PresalesCommand**: Spring Shell entry points:
    - `presales-ingest --type [TECH|PRODUCT] --path <dir>`: Index documents into specific RAG instances.
    - `presales-start --email-path <file> --ws <name>`: Start the refinement phase.
    - `presales-resume --ws <name>`: Start the analysis phase.
- **LocalRagTools**: Utilized to manage `tech-ref` and `product-spec` Lucene instances.

## 5. Directory Structure
```text
output/presales/{ws_name}/
├── state.json          # Current phase and metadata
├── email_source.txt    # Original customer request
├── crs.md              # Refined requirements (User editable)
├── analysis.md         # Gap and M/M analysis
├── questions.md        # Questions for the customer
└── final_report.md     # Final response in customer's language
```

## 6. Success Criteria
- Accurately interprets technical requirements using RAG.
- Provides realistic effort estimations based on product knowledge.
- Seamlessly handles English and Korean (and other languages) as per customer input.
- Allows interruption and manual correction via file editing.
