package io.autocrypt.jwlee.cowork.apiagent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.prompt.persona.RoleGoalBackstory;
import com.embabel.common.ai.model.LlmOptions;
import io.autocrypt.jwlee.cowork.apiagent.domain.ApiRequest;
import io.autocrypt.jwlee.cowork.apiagent.domain.ApiResult;
import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;
import io.autocrypt.jwlee.cowork.core.tools.CoworkLogger;
import io.autocrypt.jwlee.cowork.core.tools.FileReadTool;
import io.autocrypt.jwlee.cowork.core.tools.GrepTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Agent(description = "Discovers and analyzes all API endpoints in the codebase.")
@Component
public class ApiAgent {

    private final GrepTool grepTool;
    private final FileReadTool readTool;
    private final PromptProvider promptProvider;
    private final RoleGoalBackstory persona;
    private final CoworkLogger logger;
    private final com.embabel.agent.core.AgentPlatform agentPlatform;

    public ApiAgent(GrepTool grepTool, FileReadTool readTool, PromptProvider promptProvider, CoworkLogger logger, com.embabel.agent.core.AgentPlatform agentPlatform) {
        this.grepTool = grepTool;
        this.readTool = readTool;
        this.promptProvider = promptProvider;
        this.logger = logger;
        this.agentPlatform = agentPlatform;
        this.persona = promptProvider.getPersona("agents/api/persona.md");
    }

    // --- DTOs for LLM Extraction ---

    public record ParameterInfo(String name, String type, boolean required, String description) {}

    public record EndpointInfo(String method, String path, List<ParameterInfo> parameters, String returnType, String description, String sourceFile) {}

    public record ControllerBatch(String controllerName, String basePath, List<EndpointInfo> endpoints) {}

    public record ExtractedApiBatch(List<ControllerBatch> controllers) {}

    // --- States ---

    @State
    public record ApiDiscoveryState(ApiRequest request, List<String> controllerFiles, String techStack) {}

    @State
    public record ApiExtractionState(ApiRequest request, List<ExtractedApiBatch> batches) {}

    @Action(description = "Stage 0: Context Priming via ArchitectureAgent.")
    public ApiDiscoveryState prepareContext(ApiRequest request, Ai ai) {
        String finalContext = request.context();
        
        if (finalContext == null || finalContext.trim().length() < 10) {
            logger.info("ApiAgent", "Stage 0: Context is empty or too short. Invoking ArchitectureAgent for structural priming...");
            try {
                var archInvocation = com.embabel.agent.api.invocation.AgentInvocation.create(agentPlatform, io.autocrypt.jwlee.cowork.architectureagent.domain.ArchitectureReport.class);
                var archReport = archInvocation.invoke(new io.autocrypt.jwlee.cowork.architectureagent.domain.ArchitectureRequest(request.path(), "General analysis for API context priming"));
                
                StringBuilder primedContext = new StringBuilder("Auto-generated Architecture Context:\n");
                primedContext.append("Summary: ").append(archReport.summary()).append("\n");
                primedContext.append("Tech Stack: ").append(archReport.technicalStack()).append("\n");
                
                if (archReport.modules() != null && !archReport.modules().isEmpty()) {
                    primedContext.append("Key Modules:\n");
                    for (var mod : archReport.modules()) {
                        primedContext.append("- ").append(mod.name()).append(": ").append(mod.responsibility()).append("\n");
                    }
                }
                finalContext = primedContext.toString();
                logger.info("ApiAgent", "Context successfully primed by ArchitectureAgent.");
            } catch (Exception e) {
                logger.info("ApiAgent", "Architecture priming failed, proceeding with original context. Error: " + e.getMessage());
            }
        }
        
        ApiRequest updatedRequest = new ApiRequest(request.path(), finalContext);
        return discoverControllers(updatedRequest, ai);
    }

    @Action(description = "Stage 1: Discovering API controllers and technology stack.")
    public ApiDiscoveryState discoverControllers(ApiRequest request, Ai ai) {
        logger.info("ApiAgent", "Stage 1: Discovering controllers and tech stack...");
        
        List<String> rawControllerGrep = new ArrayList<>();
        // Java/Spring
        rawControllerGrep.addAll(grepTool.grep("@RestController", request.path()));
        rawControllerGrep.addAll(grepTool.grep("@Controller", request.path()));
        // Python/FastAPI/Flask
        rawControllerGrep.addAll(grepTool.grep("@app\\.", request.path()));
        rawControllerGrep.addAll(grepTool.grep("@router\\.", request.path()));
        // Node/Express (common patterns)
        rawControllerGrep.addAll(grepTool.grep("router\\.(get|post|put|delete)\\(", request.path()));

        List<String> controllerFiles = extractUniqueFiles(rawControllerGrep).stream()
                .filter(this::isValidSourceFile)
                .collect(Collectors.toList());

        String techStackPrompt = "Analyze the project path: " + request.path() + " and context: " + request.context() + " to identify the main API technology stack (e.g., Spring Boot, FastAPI, Express).";
        String techStack = ai.withLlmByRole("normal").generateText(techStackPrompt);

        logger.info("ApiAgent", "Found " + controllerFiles.size() + " potential controller files. Tech Stack: " + techStack);
        return new ApiDiscoveryState(request, controllerFiles, techStack);
    }

    @Action(description = "Stage 2: Parsing controllers with LLM in parallel batches.")
    public ApiExtractionState extractEndpoints(ApiDiscoveryState state, Ai ai) {
        logger.info("ApiAgent", "Stage 2: Parsing controllers with LLM in parallel batches...");

        int batchSize = 5;
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < state.controllerFiles().size(); i += batchSize) {
            chunks.add(state.controllerFiles().subList(i, Math.min(i + batchSize, state.controllerFiles().size())));
        }

        List<ExtractedApiBatch> allParsedBatches = chunks.parallelStream().map(chunk -> {
            StringBuilder chunkContent = new StringBuilder();
            for (String file : chunk) {
                try {
                    String content = readTool.readFile(file).content();
                    chunkContent.append("--- File: ").append(file).append(" ---\n");
                    chunkContent.append(content).append("\n\n");
                } catch (Exception ignored) {}
            }

            String prompt = promptProvider.getPrompt("agents/api/extract-endpoints-batch.jinja", Map.of(
                "sourceCode", truncate(chunkContent.toString(), 60000)
            ));

            try {
                return ai.withLlm(LlmOptions.withLlmForRole("performant").withoutThinking().withMaxTokens(65536))
                        .creating(ExtractedApiBatch.class)
                        .fromPrompt(prompt);
            } catch (Exception e) {
                logger.info("ApiAgent", "Batch parsing failed: " + e.getMessage());
                return new ExtractedApiBatch(new ArrayList<>());
            }
        }).collect(Collectors.toList());

        return new ApiExtractionState(state.request(), allParsedBatches);
    }

    @AchievesGoal(description = "Generate the final API documentation report.")
    @Action(description = "Stage 3: Synthesizing extraction results into a final report.")
    public ApiResult synthesizeApiReport(ApiExtractionState state, Ai ai) {
        logger.info("ApiAgent", "Stage 3: Synthesizing final API report...");

        StringBuilder batchesSummary = new StringBuilder();
        for (ExtractedApiBatch batch : state.batches()) {
            if (batch.controllers() != null) {
                for (var controller : batch.controllers()) {
                    batchesSummary.append("Controller: ").append(controller.controllerName()).append(" (Base: ").append(controller.basePath()).append(")\n");
                    if (controller.endpoints() != null) {
                        for (var ep : controller.endpoints()) {
                            batchesSummary.append("- ").append(ep.method()).append(" ").append(ep.path()).append(": ").append(ep.description()).append("\n");
                        }
                    }
                }
            }
        }

        String prompt = promptProvider.getPrompt("agents/api/synthesize-report.jinja", Map.of(
            "context", state.request().context(),
            "batchesSummary", truncate(batchesSummary.toString(), 40000)
        ));

        try {
            String report = ai.withLlm(LlmOptions.withLlmForRole("performant").withoutThinking().withMaxTokens(65536))
                    .withPromptContributor(persona)
                    .generateText(prompt);
            return new ApiResult(report, "Success");
        } catch (Exception e) {
            return new ApiResult("Report generation failed: " + e.getMessage(), "Error");
        }
    }

    // --- Helpers ---

    private List<String> extractUniqueFiles(List<String> grepLines) {
        return grepLines.stream().filter(line -> line.contains(":")).map(line -> line.split(":", 2)[0]).distinct().collect(Collectors.toList());
    }

    private boolean isValidSourceFile(String path) {
        String p = path.toLowerCase();
        return !p.contains("/target/") && !p.contains("/build/") && !p.contains("\\target\\") && !p.contains("\\build\\")
               && !p.contains("/test/") && !p.contains("architecturetest");
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n... [TRUNCATED]";
    }
}
