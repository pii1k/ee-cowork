package io.autocrypt.jwlee.cowork.architectureagent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.prompt.persona.RoleGoalBackstory;
import io.autocrypt.jwlee.cowork.architectureagent.domain.ArchitectureReport;
import io.autocrypt.jwlee.cowork.architectureagent.domain.ArchitectureRequest;
import io.autocrypt.jwlee.cowork.core.tools.FileReadTool;
import io.autocrypt.jwlee.cowork.core.tools.GlobTool;
import io.autocrypt.jwlee.cowork.core.tools.GrepTool;
import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Multi-stage Agent specialized in codebase architecture analysis.
 */
@Agent(description = "Analyzes codebase architecture through a rigorous multi-stage exploration process.")
@Component
public class ArchitectureAgent {

    private final RoleGoalBackstory persona;
    private final FileReadTool readTool;
    private final GlobTool globTool;
    private final GrepTool grepTool;

    public ArchitectureAgent(PromptProvider promptProvider,
                             FileReadTool readTool,
                             GlobTool globTool,
                             GrepTool grepTool) {
        this.persona = promptProvider.getPersona("agents/architecture/persona.md");
        this.readTool = readTool;
        this.globTool = globTool;
        this.grepTool = grepTool;
    }

    // --- State Objects defining the workflow ---

    @State
    public record InitialBlueprint(
        String projectPath,
        String userContext,
        List<String> keyFiles,
        List<String> suspectedModules,
        String hypothesizedTechStack
    ) {}

    @State
    public record VerifiedEvidence(
        InitialBlueprint blueprint,
        String deepInspectionNotes,
        String actualDependencies,
        String actualEntryPoints
    ) {}

    // --- Actions (The Pipeline) ---

    @Action(description = "Stage 1: Maps the broad directory structure and configuration files.")
    public InitialBlueprint mapProjectStructure(ArchitectureRequest request, Ai ai) {
        String prompt = """
                STAGE 1: PROJECT MAPPING
                Path: %s
                Context: %s
                
                Your only job right now is to understand the macro-structure of the project.
                1. Use 'glob' to find all files and map the directory tree.
                2. Use 'readFile' on top-level configuration files (like pom.xml, build.gradle, README.md).
                3. Identify the main technology stack and what appear to be the primary logical modules/packages.
                
                DO NOT try to guess module internals yet. Just map the surface.
                """.formatted(request.path(), request.context());

        return ai.withLlmByRole("normal")
                .withPromptContributor(persona)
                .withToolObject(globTool)
                .withToolObject(readTool)
                .createObject(prompt, InitialBlueprint.class);
    }

    @Action(description = "Stage 2: Deep dives into specific modules to verify dependencies and responsibilities by reading actual code.")
    public VerifiedEvidence deepDiveModules(InitialBlueprint blueprint, Ai ai) {
        String prompt = """
                STAGE 2: DEEP INSPECTION & VERIFICATION
                
                You previously identified these suspected modules: %s
                And this hypothesized stack: %s
                
                Now, you MUST prove your hypotheses by looking inside the actual code.
                1. Use 'grep' to search for key annotations (e.g., @Service, @Component, @Controller, @Agent).
                2. Use 'readFile' to open the core classes within the suspected modules.
                3. Look at their 'import' statements to map the REAL dependencies between these modules.
                4. Find the actual entry point (e.g., the class with 'public static void main' or core API endpoints).
                
                Write down your findings, specifically noting the true dependencies and entry points based on the code you read.
                """.formatted(blueprint.suspectedModules(), blueprint.hypothesizedTechStack());

        return ai.withLlmByRole("normal")
                .withPromptContributor(persona)
                .withToolObject(grepTool)
                .withToolObject(readTool)
                .createObject(prompt, VerifiedEvidence.class);
    }

    @AchievesGoal(description = "Stage 3: Compiles the final structured architecture report based on verified evidence.")
    @Action(description = "Compiles the final architecture report.")
    public ArchitectureReport compileReport(VerifiedEvidence evidence, Ai ai) {
        String prompt = """
                STAGE 3: FINAL REPORT COMPILATION
                
                You have gathered the macro-structure: %s
                And you have performed deep code inspections resulting in these notes: %s
                Actual Dependencies: %s
                Actual Entry Points: %s
                
                Synthesize this hard evidence into the final structured Architecture Report.
                Do not make any further assumptions. Your report must reflect the realities you observed during your deep dive.
                
                # IMPORTANT: LANGUAGE REQUIREMENT
                You MUST write the entire report in **KOREAN** (한국어).
                Ensure the summary, responsibility descriptions, and recommendations are all translated into professional Korean architecture terminology.
                """.formatted(
                        evidence.blueprint().hypothesizedTechStack() + " | Modules: " + evidence.blueprint().suspectedModules(),
                        evidence.deepInspectionNotes(),
                        evidence.actualDependencies(),
                        evidence.actualEntryPoints()
                );

        // No tools provided in this final compilation stage. Just synthesis.
        return ai.withLlmByRole("normal")
                .withPromptContributor(persona)
                .createObject(prompt, ArchitectureReport.class);
    }
}
