package io.autocrypt.jwlee.cowork.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.CoreToolGroups;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.rag.tools.ToolishRag;
import io.autocrypt.jwlee.cowork.service.SlideFileService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.Pattern;

@Agent(description = "Advanced Slides professional creator. Uses deterministic wrapping for templates.")
public class PresentationAgent {

    private final ToolishRag localKnowledgeTool;
    private final SlideFileService fileService;

    public PresentationAgent(ToolishRag localKnowledgeTool, SlideFileService fileService) {
        this.localKnowledgeTool = localKnowledgeTool;
        this.fileService = fileService;
    }

    /**
     * Structured slide content. Logic in toMarkdown() ensures correct formatting without AI retries.
     */
    public record SlidePage(
            int pageNumber,
            String templateName,
            String titleText,
            String leftContent,
            String rightContent,
            String fullWidthContent,
            String footerSource
    ) {
        /**
         * Converts the structured fields into the final markdown format.
         * Ensures the title always starts with '## ' deterministically.
         */
        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            
            // Deterministic title fix: ensure it starts with ## 
            String fixedTitle = titleText != null ? titleText.trim() : "";
            if (!fixedTitle.startsWith("##")) {
                fixedTitle = "## " + fixedTitle;
            } else if (fixedTitle.startsWith("##") && !fixedTitle.startsWith("## ")) {
                // Fix cases like "##제목" to "## 제목"
                fixedTitle = "## " + fixedTitle.substring(2).trim();
            }
            
            sb.append("::: title\n").append(fixedTitle).append("\n:::\n\n");

            // Handle content based on template type
            if (templateName != null && (templateName.contains("3-2") || templateName.contains("2-1"))) {
                if (leftContent != null && !leftContent.isBlank()) {
                    sb.append("::: left\n").append(leftContent).append("\n:::\n\n");
                }
                if (rightContent != null && !rightContent.isBlank()) {
                    sb.append("::: right\n").append(rightContent).append("\n:::\n\n");
                }
            }
            
            if (fullWidthContent != null && !fullWidthContent.isBlank()) {
                sb.append("::: block\n").append(fullWidthContent).append("\n:::\n\n");
            }
            
            if (footerSource != null && !footerSource.isBlank()) {
                sb.append("::: source\n").append(footerSource).append("\n:::\n");
            }
            
            return sb.toString();
        }
    }

    public record PresentationSettings(String content) {}
    public record PresentationPlan(String title, String subtitle, List<String> pageTopics) {}
    public record FinalPresentation(String filePath) {}

    @Action
    public PresentationSettings initializeSettings(UserInput input) throws IOException {
        String goldenSettings = """
                ---
                theme: consult
                height: 540
                margin: 0
                maxScale: 4
                mermaid:
                  themeVariables:
                    fontSize: 14px
                  flowchart: 
                    useMaxWidth: false
                    nodeSpacing: 50
                    rankSpacing: 80
                ---
                <style>
                .horizontal_dotted_line{ border-bottom: 2px dotted gray; }
                .small-indent p { margin: 0; }
                .small-indent ul { padding-left: 1em; line-height: 1.3; }
                .small-indent ul > li { padding: 0; }
                ul p { margin-top: 0; }
                .force-center { display: flex !important; flex-direction: column; justify-content: center; align-items: center; width: 100%; height: 100%; text-align: center; }
                </style>
                """;
        fileService.saveSettings(goldenSettings);
        return new PresentationSettings(goldenSettings);
    }

    @Action
    public PresentationPlan planPresentation(UserInput input, PresentationSettings settings, Ai ai) {
        return ai.withAutoLlm()
                .withReference(localKnowledgeTool)
                .createObject(String.format("""
                        Plan a professional presentation structure for: %s. 
                        
                        # OUTPUT REQUIREMENTS:
                        1. Provide a catchy 'title' and a descriptive 'subtitle'.
                        2. List the core 'pageTopics' for the content slides in KOREAN.
                        3. The FIRST topic after the title MUST be "Table of Contents" or "Objectives" (목차/목표).
                        4. DO NOT include the title slide itself in 'pageTopics'.
                        """, input.getContent()), PresentationPlan.class);
    }

    @Action
    public List<SlidePage> generateAllSlides(PresentationPlan plan, PresentationSettings settings, Ai ai) throws IOException {
        List<SlidePage> allPages = new ArrayList<>();

        // 1. Deterministic Title Page (Page 1)
        String today = java.time.LocalDate.now().toString();
        String titleMarkdown = String.format("## %s\n::: block\n#### %s / %s\n:::", plan.title(), plan.subtitle(), today);
        fileService.savePage(1, "tpl-con-title", titleMarkdown);
        
        // 2. Pre-fetch ALL Context (ONE RAG call instead of N calls)
        // Dynamically search based on the plan topics, not a hardcoded document name.
        String sharedContext = ai.withAutoLlm()
                .withReference(localKnowledgeTool)
                .generateText(String.format("""
                        Search the local knowledge base for information related to: '%s'.
                        Specific topics: %s.
                        
                        # INSTRUCTIONS:
                        - Summarize ONLY relevant facts found in the documents.
                        - If NO relevant documents are found at all, return exactly the word 'NONE'.
                        - Be concise to save tokens.
                        """, plan.title(), String.join(", ", plan.pageTopics())));

        boolean hasLocalContext = sharedContext != null && !sharedContext.trim().equalsIgnoreCase("NONE");

        // Examples for validation and few-shot
        SlidePage goodExample = new SlidePage(
            2, 
            "tpl-con-3-2", 
            "## 주요 성과", 
            """
            - 처리 속도 30% 향상
            - 오류율 5% 감소
            """, 
            "성능 비교 그래프 참고", 
            null, 
            "PoC Phase 1 보고서"
        );

        // 3. AI Generated Content Pages
        for (int i = 0; i < plan.pageTopics().size(); i++) {
            // Rate Limit Mitigation: Sleep between slide generations
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            int pageNum = i + 2; 
            String topic = plan.pageTopics().get(i);
            
            String contextSection = hasLocalContext ? 
                String.format("# SHARED CONTEXT FROM DOCUMENTS:\n%s", sharedContext) : 
                "# NOTE: No relevant local documents found. Use your general knowledge.";

            SlidePage page = ai.withAutoLlm()
                    .creating(SlidePage.class)
                    .withExample("Properly structured Korean slide", goodExample)
                    .fromPrompt(String.format("""
                            You are crafting Page %d for the presentation: '%s'.
                            TOPIC: %s
                            
                            %s
                            
                            # TEMPLATE GUIDE & CONSTRAINTS:
                            - tpl-con-default-box: 
                                * Usage: Table of Contents (TOC), Objectives, or Core Goals.
                                * Formatting: Use 'fullWidthContent' to list items. These will be wrapped in '::: block'.
                            - tpl-con-default-slide: 
                                * Usage: General content, lists, large diagrams, TABLES, or LARGE IMAGES.
                                * Capacity: Max 10 lines of text.
                                * Mermaid: FULLY SUPPORTED.
                                * Tables: MARKDOWN TABLES ARE FULLY SUPPORTED for any structured data.
                            - tpl-con-3-2: 
                                * Usage: Comparison or description (Left) + summary (Right).
                                * Capacity: Left (5-7 lines), Right (3-5 lines).
                                * Mermaid: NOT RECOMMENDED.
                                * Tip: You can put an image '![..](..)' on the LEFT and text on the RIGHT.
                            - tpl-con-2-1-box: 
                                * Usage: Detailed visual (Left) + Highlight box (Right).
                                * Capacity: Left (Supports large diagrams, 10 lines, or FEATURED IMAGE), Right Box (3-4 punchy lines).
                                * Mermaid: SUPPORTED on the LEFT side.
                                * Tip: Perfect for a large visual on the left with a summary box on the right.
                            
                            # YOUR MISSION:
                            1. Fill 'titleText' in KOREAN (starts with '## ').
                            2. Select the BEST 'templateName' based on the guide above. For TOC or Objectives, ALWAYS use 'tpl-con-default-box'.
                            3. Fill content fields in KOREAN. Use Mermaid or MARKDOWN TABLES for any structured or complex data.
                            4. IMAGES: You can use standard Markdown syntax '![alt text](url)' to include relevant web images if it helps visualize the topic.
                            5. CRITICAL: USE ACTUAL NEWLINES for lists and paragraphs. DO NOT use the '\\n' character string.
                            5. If 'SHARED CONTEXT' is provided, prioritize those facts. Otherwise, use your internal knowledge about '%s'.
                            6. Cite the source document filename in 'footerSource'.
                            
                            # FOOTER SOURCE:
                            - Provide the origin document name in 'footerSource'. It will be wrapped in '::: source'.
                            """, pageNum, plan.title(), topic, contextSection, topic));
            
            fileService.savePage(page.pageNumber(), page.templateName(), page.toMarkdown());
            allPages.add(page);
        }
        return allPages;
    }

    @AchievesGoal(description = "Merged professional presentation")
    @Action
    public FinalPresentation finishPresentation(List<SlidePage> allSlides) throws IOException {
        return new FinalPresentation(fileService.mergeAll());
    }

    @Action
    public SlidePage modifyExistingSlide(UserInput input, PresentationSettings settings, Ai ai) throws IOException {
        Integer targetPage = ai.withAutoLlm().creating(Integer.class).fromPrompt("Which page number to modify? " + input.getContent());
        String currentRawFile = fileService.readPage(targetPage);
        
        SlidePage updated = ai.withAutoLlm()
                .withReference(localKnowledgeTool)
                .creating(SlidePage.class)
                .fromPrompt(String.format("""
                        Modify Page %d of the presentation.
                        CURRENT RAW CONTENT:
                        %s
                        
                        REQUEST: %s
                        
                        # RULES:
                        1. Fill 'titleText' (Korean, starts with ##).
                        2. Select 'templateName'.
                        3. Fill 'leftContent', 'rightContent', or 'fullWidthContent' as appropriate.
                        4. ALL text content MUST BE in KOREAN.
                        """, targetPage, currentRawFile, input.getContent()));
        
        fileService.savePage(updated.pageNumber(), updated.templateName(), updated.toMarkdown());
        return updated;
    }
}
