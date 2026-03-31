package io.autocrypt.jwlee.cowork.web.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.workflow.loop.RepeatUntilAcceptableBuilder;
import com.embabel.agent.api.common.workflow.loop.TextFeedback;
import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.common.ai.model.LlmOptions;
import io.autocrypt.jwlee.cowork.web.core.prompts.PromptProvider;
import io.autocrypt.jwlee.cowork.web.core.tools.CoworkLogger;
import io.autocrypt.jwlee.cowork.web.core.tools.LocalRagTools;
import io.autocrypt.jwlee.cowork.web.core.workaround.JsonSafeToolishRag;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class PresalesWebAgent implements io.autocrypt.jwlee.cowork.web.agent.Agent {

    private final LocalRagTools localRagTools;
    private final CoworkLogger logger;
    private final AgentPlatform agentPlatform;

    public PresalesWebAgent(LocalRagTools localRagTools, CoworkLogger logger, AgentPlatform agentPlatform) {
        this.localRagTools = localRagTools;
        this.logger = logger;
        this.agentPlatform = agentPlatform;
    }

    @Override
    public String getId() { return "presales"; }

    @Override
    public String getName() { return "Presales 갭 분석 에이전트"; }

    @Override
    public String getDescription() { return "고객 요구사항과 업로드된 자사 기술 문서를 분석하여 차이점(Gap)을 도출합니다."; }

    @Override
    public String getRole() { return "Technical Sales"; }

    // DTOs for the 2-step process (Matching original PresalesAgent logic)
    public record RequirementRequest(String sourceContent, String techRagName) {}
    public record CrsResult(String content) {}
    public record GapAnalysisRequest(String crsContent, String productRagName) {}
    public record AnalysisResult(String finalReport) {}

    @Agent(description = "Internal Presales Logic")
    @Component
    public static class PresalesLogicAgent {
        private final LocalRagTools localRagTools;
        private final PromptProvider promptProvider;

        public PresalesLogicAgent(LocalRagTools localRagTools, PromptProvider promptProvider) {
            this.localRagTools = localRagTools;
            this.promptProvider = promptProvider;
        }

        @AchievesGoal(description = "Refined CRS generated")
        @Action
        public CrsResult refineRequirements(RequirementRequest req, ActionContext ctx) throws Exception {
            var techSearch = localRagTools.getOrOpenMemoryInstance(req.techRagName());
            var techRag = new JsonSafeToolishRag("tech_knowledge", "Standard technical specifications", techSearch);

            var simpleAi = ctx.ai().withLlmByRole("simple").withReference(techRag);
            var normalAi = ctx.ai().withLlm(LlmOptions.withLlmForRole("normal").withMaxTokens(16384));

            String techContext = RepeatUntilAcceptableBuilder
                    .returning(String.class)
                    .withMaxIterations(2)
                    .withScoreThreshold(0.7)
                    .repeating(loopCtx -> {
                        String prompt = promptProvider.getPrompt("agents/presales/refine-requirements-search.jinja", Map.of(
                            "sourceContent", req.sourceContent(),
                            "lastFindings", loopCtx.lastAttempt() != null ? loopCtx.lastAttempt().getResult() : "None",
                            "feedback", loopCtx.lastAttempt() != null ? loopCtx.lastAttempt().getFeedback().toString() : "Initial"
                        ));
                        return simpleAi.generateText(prompt);
                    })
                    .withEvaluator(loopCtx -> {
                        String prompt = promptProvider.getPrompt("agents/presales/refine-requirements-eval.jinja", Map.of(
                            "sourceContent", req.sourceContent(),
                            "contextToEvaluate", loopCtx.getResultToEvaluate()
                        ));
                        return normalAi.createObject(prompt, TextFeedback.class);
                    })
                    .build()
                    .asSubProcess(ctx, String.class);

            String finalPrompt = promptProvider.getPrompt("agents/presales/refine-requirements-final.jinja", Map.of(
                "sourceContent", req.sourceContent(),
                "techContext", techContext
            ));

            return new CrsResult(normalAi.generateText(finalPrompt));
        }

        @AchievesGoal(description = "Gap analysis report generated")
        @Action
        public AnalysisResult analyzeGapAndFinalize(GapAnalysisRequest req, ActionContext ctx) throws Exception {
            var productSearch = localRagTools.getOrOpenMemoryInstance(req.productRagName());
            var productRag = new JsonSafeToolishRag("product_knowledge", "Internal product features", productSearch);

            var simpleAi = ctx.ai().withLlmByRole("simple").withReference(productRag);
            var normalAi = ctx.ai().withLlm(LlmOptions.withLlmForRole("normal").withMaxTokens(16384));

            String productContext = RepeatUntilAcceptableBuilder
                    .returning(String.class)
                    .withMaxIterations(2)
                    .withScoreThreshold(0.7)
                    .repeating(loopCtx -> {
                        String prompt = promptProvider.getPrompt("agents/presales/gap-analysis-search.jinja", Map.of(
                            "crsContent", req.crsContent(),
                            "lastFindings", loopCtx.lastAttempt() != null ? loopCtx.lastAttempt().getResult() : "None",
                            "feedback", loopCtx.lastAttempt() != null ? loopCtx.lastAttempt().getFeedback().toString() : "Initial"
                        ));
                        return simpleAi.generateText(prompt);
                    })
                    .withEvaluator(loopCtx -> {
                        String prompt = promptProvider.getPrompt("agents/presales/gap-analysis-eval.jinja", Map.of(
                            "crsContent", req.crsContent(),
                            "contextToEvaluate", loopCtx.getResultToEvaluate()
                        ));
                        return normalAi.createObject(prompt, TextFeedback.class);
                    })
                    .build()
                    .asSubProcess(ctx, String.class);

            String finalReportPrompt = promptProvider.getPrompt("agents/presales/gap-analysis-combined.jinja", Map.of(
                "crsContent", req.crsContent(),
                "productContext", productContext
            ));

            return new AnalysisResult(normalAi.generateText(finalReportPrompt));
        }
    }

    @Override
    @Async
    public CompletableFuture<String> execute(Map<String, String> params) {
        String sourceContent = params.getOrDefault("content", "");
        String techFilePaths = params.get("tech_ref_file");
        String productFilePaths = params.get("product_spec_file");
        String taskId = params.get("taskId");

        return CompletableFuture.supplyAsync(() -> {
            String techRagName = "tech-mem-" + taskId;
            String productRagName = "product-mem-" + taskId;

            try {
                if (techFilePaths != null && !techFilePaths.isEmpty()) {
                    for (String path : techFilePaths.split(",")) {
                        localRagTools.ingestUrlToMemory(path.trim(), techRagName);
                    }
                }
                if (productFilePaths != null && !productFilePaths.isEmpty()) {
                    for (String path : productFilePaths.split(",")) {
                        localRagTools.ingestUrlToMemory(path.trim(), productRagName);
                    }
                }

                // Step 1: Invoke Refinement (Matches original Command flow)
                var refineInvocation = AgentInvocation.create(agentPlatform, CrsResult.class);
                var process1 = refineInvocation.runAsync(new RequirementRequest(sourceContent, techRagName)).get();
                while (!process1.getFinished()) { Thread.sleep(500); }
                String crs = process1.resultOfType(CrsResult.class).content();

                // Step 2: Invoke Gap Analysis (Matches original Command flow)
                var analysisInvocation = AgentInvocation.create(agentPlatform, AnalysisResult.class);
                var process2 = analysisInvocation.runAsync(new GapAnalysisRequest(crs, productRagName)).get();
                while (!process2.getFinished()) { Thread.sleep(500); }
                
                return process2.resultOfType(AnalysisResult.class).finalReport();
            } catch (Exception e) {
                logger.error("PresalesAgent", "Execution failed: " + e.getMessage());
                throw new RuntimeException(e);
            } finally {
                localRagTools.closeMemoryInstance(techRagName);
                localRagTools.closeMemoryInstance(productRagName);
                deleteTempFiles(techFilePaths);
                deleteTempFiles(productFilePaths);
            }
        });
    }

    private void deleteTempFiles(String filePaths) {
        if (filePaths != null && !filePaths.isEmpty()) {
            for (String path : filePaths.split(",")) {
                try { java.nio.file.Files.deleteIfExists(Paths.get(path.trim())); } catch (Exception e) { }
            }
        }
    }
}
