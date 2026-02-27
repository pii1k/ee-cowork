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
import java.util.List;

@Agent(description = "Agent that creates and modifies presentation slides using Advanced Slides syntax")
public class PresentationAgent {

    private final ToolishRag localKnowledgeTool;
    private final SlideFileService fileService;

    public PresentationAgent(ToolishRag localKnowledgeTool, SlideFileService fileService) {
        this.localKnowledgeTool = localKnowledgeTool;
        this.fileService = fileService;
    }

    public record SlidePage(int pageNumber, String markdown) {}
    public record PresentationSettings(String content) {}
    public record FinalPresentation(String filePath) {}

    /**
     * Sets the default YAML header and style tags using a fixed template.
     */
    @Action
    public PresentationSettings initializeSettings(UserInput input) throws IOException {
        String fixedSettings = """
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
                .force-center { display: flex !important; flex-direction: column; justify-content: center; align-items: center; width: 100%; height: 100%; text-align: center; }
                </style>
                """;
        fileService.saveSettings(fixedSettings);
        return new PresentationSettings(fixedSettings);
    }

    /**
     * Action to modify an existing slide page based on user request.
     */
    @Action
    public SlidePage modifyExistingSlide(UserInput input, PresentationSettings settings, Ai ai) throws IOException {
        // Corrected: use creating().fromPrompt() instead of generateObject()
        Integer targetPage = ai.withAutoLlm()
                .creating(Integer.class)
                .fromPrompt("Extract only the page number as an integer from this request: " + input.getContent());
        
        String currentContent = fileService.readPage(targetPage);
        
        SlidePage updated = ai.withAutoLlm()
                .withReference(localKnowledgeTool)
                .creating(SlidePage.class)
                .fromPrompt(String.format("""
                        Modify the following presentation slide based on this request: %s
                        
                        # Style Rules:
                        1. CONSULT 'catalog.md' to maintain the correct template layout and container names.
                        2. Keep using '::: title', '::: left', '::: right', '::: block' containers as appropriate.
                        3. Respect the existing Advanced Slides syntax.
                        
                        # Current content of page %d:
                        %s
                        
                        Return the updated content as a SlidePage object with pageNumber %d.
                        """, input.getContent(), targetPage, currentContent, targetPage));
        
        fileService.savePage(updated.pageNumber(), updated.markdown());
        return updated;
    }

    /**
     * Action to create a brand new slide by intelligently picking a template.
     */
    @Action
    public SlidePage createNewSlide(UserInput input, PresentationSettings settings, Ai ai) throws IOException {
        SlidePage page = ai.withAutoLlm()
                .withReference(localKnowledgeTool)
                .withToolGroup(CoreToolGroups.WEB)
                .creating(SlidePage.class)
                .fromPrompt(String.format("""
                        Create a professional presentation slide based on: %s
                        
                        # Instructions:
                        1. CONSULT the 'catalog.md' in knowledge base to pick the most suitable template for the content's purpose.
                        2. USE EXACTLY one of the templates (e.g., tpl-con-title, tpl-con-splash, tpl-con-3-2, tpl-con-default-box, tpl-con-2-1-box).
                        3. MAP the content to the template's containers (::: title, ::: left, ::: right, ::: block, etc.).
                        4. If no specialized template fits, DEFAULT to 'tpl-con-default-slide'.
                        
                        Return a SlidePage object with the selected markdown and a pageNumber.
                        """, input.getContent()));
        
        fileService.savePage(page.pageNumber(), page.markdown());
        return page;
    }

    @AchievesGoal(description = "The presentation has been merged into a final file")
    @Action
    public FinalPresentation mergePresentation(SlidePage lastUpdated) throws IOException {
        String path = fileService.mergeAll();
        return new FinalPresentation(path);
    }
}
