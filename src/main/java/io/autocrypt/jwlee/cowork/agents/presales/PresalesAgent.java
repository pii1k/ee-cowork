package io.autocrypt.jwlee.cowork.agents.presales;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;

import io.autocrypt.jwlee.cowork.core.tools.LocalRagTools;
import io.autocrypt.jwlee.cowork.core.workaround.JsonSafeToolishRag;

@Agent(description = "Presales Engineering Agent for requirement analysis and gap assessment")
@Component
public class PresalesAgent {

    private final LocalRagTools localRagTools;

    public PresalesAgent(LocalRagTools localRagTools) {
        this.localRagTools = localRagTools;
    }

    /**
     * Phase 1: Refine customer email into a technical CRS using technical reference RAG at the given path.
     */
    @Action
    public String refineRequirements(String emailContent, Path techRagPath, Ai ai) throws IOException {
        var techSearch = localRagTools.getOrOpenInstance("tech-ref", techRagPath);
        var techRag = new JsonSafeToolishRag("tech_knowledge", "Standard technical specifications and industry knowledge", techSearch);

        String prompt = String.format("""
            You are a Senior Solutions Architect. Analyze the following customer email and refine it into a detailed Customer Requirements Specification (CRS) in Markdown format.
            
            # Instructions:
            1. Use the provided technical knowledge to clarify ambiguous terms and ensure industry-standard terminology is used (e.g., IEEE 1609.2, V2X security).
            2. Structure the CRS clearly with sections: Introduction, Functional Requirements, Non-Functional Requirements, and Technical Constraints.
            3. Do NOT include any analysis of product capabilities or gaps in this step. Focus ONLY on what the customer wants.
            4. Output ONLY the Markdown content of the CRS.
            
            # Customer Email:
            %s
            """, emailContent);

        return ai.withLlmByRole("simple")
                .withReference(techRag)
                .generateText(prompt);
    }

    /**
     * Phase 2: Perform gap analysis and estimate effort using product specification RAG at the given path.
     */
    @Action
    public AnalysisResult analyzeGapAndFinalize(String crsContent, String originalLanguage, Path productRagPath, Ai ai) throws IOException {
        var productSearch = localRagTools.getOrOpenInstance("product-spec", productRagPath);
        var productRag = new JsonSafeToolishRag("product_knowledge", "Internal product features, roadmap, and technical specifications", productSearch);

        String analysisPrompt = String.format("""
            Analyze the following CRS against our product capabilities.
            
            # Tasks:
            1. **Gap Analysis**: Identify which requirements are 'Supported', 'Partially Supported (Customization Needed)', or 'Unsupported'.
            2. **Effort Estimation**: For each gap or customization, estimate the development effort in Man-Months (M/M) with a brief technical justification.
            3. **Customer Questions**: List any ambiguous points that require further clarification from the customer.
            
            # CRS Content:
            %s
            
            # Output Format (Markdown):
            Provide a detailed report including Gap Analysis and Effort Estimation.
            """, crsContent);

        String analysis = ai.withLlmByRole("simple")
                .withReference(productRag)
                .generateText(analysisPrompt);

        String questionPrompt = "Extract ONLY the customer questions from the following analysis result into a bulleted list: \n\n" + analysis;
        String questions = ai.withLlmByRole("simple").generateText(questionPrompt);

        String finalReportPrompt = String.format("""
            Based on the analysis and the original CRS, draft a professional final response to the customer.
            
            # Instructions:
            1. The response must be written in the Korean, even if customer's original language is %s.
            2. Maintain a professional, helpful, and confident tone.
            3. Summarize our proposal, highlight supported features, and politely address areas needing clarification or custom development.
            4. Do NOT include detailed M/M estimates in the customer-facing report unless absolutely necessary. Focus on value and solution fit.
            
            # Context (Analysis Result):
            %s
            """, originalLanguage, analysis);

        String finalReport = ai.withLlmByRole("simple").generateText(finalReportPrompt);

        return new AnalysisResult(analysis, questions, finalReport);
    }

    public record AnalysisResult(String gapAnalysis, String questions, String finalReport) {}
}
