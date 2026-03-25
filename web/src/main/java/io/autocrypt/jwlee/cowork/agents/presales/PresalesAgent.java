package io.autocrypt.jwlee.cowork.agents.presales;

import java.io.IOException;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.common.ai.model.LlmOptions;

import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;
import io.autocrypt.jwlee.cowork.core.tools.LocalRagTools;
import io.autocrypt.jwlee.cowork.core.workaround.JsonSafeToolishRag;

@Agent(description = "Presales Engineering Agent for requirement analysis and gap assessment")
@Component
public class PresalesAgent {

    private final LocalRagTools localRagTools;
    private final PromptProvider promptProvider;

    public PresalesAgent(LocalRagTools localRagTools, PromptProvider promptProvider) {
        this.localRagTools = localRagTools;
        this.promptProvider = promptProvider;
    }

    public record RequirementRequest(String sourceContent, String techRagName) {}

    public record GapAnalysisRequest(String crsContent, String originalLanguage, String productRagName) {}

    public record AnalysisResult(String gapAnalysis, String questions, String finalReport) {}

    /**
     * Phase 1: Refine customer inquiry into a technical CRS using technical reference RAG.
     * Optimized with withoutThinking() to skip tool loops for maximum speed.
     */
    @AchievesGoal(description = "Refined CRS in Markdown")
    @Action
    public String refineRequirements(RequirementRequest req, ActionContext ctx) throws IOException {
        var techSearch = localRagTools.getOrOpenMemoryInstance(req.techRagName());
        var techRag = new JsonSafeToolishRag("tech_knowledge", "Standard technical specifications and industry knowledge", techSearch);

        var simpleAi = ctx.ai().withLlm(LlmOptions.withLlmForRole("simple").withoutThinking()).withReference(techRag);
        var normalAi = ctx.ai().withLlm(LlmOptions.withLlmForRole("normal").withoutThinking());

        // 1. Gather context from RAG (Direct single-call search)
        String techContext = simpleAi.generateText(promptProvider.getPrompt("agents/presales/refine-requirements-search.jinja", Map.of(
            "sourceContent", req.sourceContent(),
            "lastFindings", "Initial search triggered.",
            "feedback", "Direct context extraction."
        )));

        // 2. Draft the final CRS
        String finalPrompt = promptProvider.getPrompt("agents/presales/refine-requirements-final.jinja", Map.of(
            "sourceContent", req.sourceContent(),
            "techContext", techContext
        ));

        return normalAi.generateText(finalPrompt);
    }

    /**
     * Phase 2: Perform gap analysis and generate an internal technical review report.
     * Unified analysis and report generation for speed.
     */
    @AchievesGoal(description = "Internal Technical Review Report completed")
    @Action
    public AnalysisResult analyzeGapAndFinalize(GapAnalysisRequest req, ActionContext ctx) throws IOException {
        var productSearch = localRagTools.getOrOpenMemoryInstance(req.productRagName());
        var productRag = new JsonSafeToolishRag("product_knowledge", "Internal product features, roadmap, and technical specifications", productSearch);

        var simpleAi = ctx.ai().withLlm(LlmOptions.withLlmForRole("simple").withoutThinking()).withReference(productRag);
        var normalAi = ctx.ai().withLlm(LlmOptions.withLlmForRole("normal").withoutThinking());

        // 1. Gather product capability information
        String productContext = simpleAi.generateText(promptProvider.getPrompt("agents/presales/gap-analysis-search.jinja", Map.of(
            "crsContent", req.crsContent(),
            "lastFindings", "Initial product capability search.",
            "feedback", "Direct search mode."
        )));

        // 2. Generate the final combined Internal Review Report
        String analysisPrompt = promptProvider.getPrompt("agents/presales/gap-analysis-worker.jinja", Map.of(
            "crsContent", req.crsContent(),
            "productContext", productContext
        ));

        // We use the 'worker' output as the foundation for the final report directly to save a call
        String rawAnalysis = normalAi.generateText(analysisPrompt);

        String finalReportPrompt = promptProvider.getPrompt("agents/presales/final-report.jinja", Map.of(
            "rawAnalysis", rawAnalysis
        ));

        String finalReport = normalAi.generateText(finalReportPrompt);

        // Questions and rawAnalysis output are removed from UI display
        return new AnalysisResult("", "", finalReport);
    }
}
