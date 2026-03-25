package io.autocrypt.jwlee.cowork.agents.translate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.RunSubagent;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.prompt.persona.RoleGoalBackstory;
import com.embabel.common.ai.model.LlmOptions;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.autocrypt.jwlee.cowork.agents.docsummary.DocSummaryAgent;
import io.autocrypt.jwlee.cowork.core.hitl.ApplicationContextHolder;
import io.autocrypt.jwlee.cowork.core.hitl.NotificationEvent;
import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;
import io.autocrypt.jwlee.cowork.core.tools.CoreWorkspaceProvider;
import io.autocrypt.jwlee.cowork.core.tools.LocalRagTools;
import io.autocrypt.jwlee.cowork.core.tools.PdfParser;

@Agent(description = "PDF 전문 번역 에이전트")
@Component
public class TranslateAgent {

    private final PdfParser parser;
    private final TranslateWorkspace workspace;
    private final ObjectMapper objectMapper;
    private final DocSummaryAgent docSummaryAgent;
    private final LocalRagTools localRagTools;
    private final PromptProvider promptProvider;
    private final RoleGoalBackstory translatorPersona;

    public TranslateAgent(PdfParser parser, TranslateWorkspace workspace, ObjectMapper objectMapper, DocSummaryAgent docSummaryAgent, LocalRagTools localRagTools, PromptProvider promptProvider) {
        this.parser = parser;
        this.workspace = workspace;
        this.objectMapper = objectMapper;
        this.docSummaryAgent = docSummaryAgent;
        this.localRagTools = localRagTools;
        this.promptProvider = promptProvider;
        this.translatorPersona = promptProvider.getPersona("agents/translate/persona.md");
    }

    public record TranslateStartRequest(String pdfPath, String workspaceName) {}
    public record TranslateResumeRequest(String workspaceName) {}

    public record DocumentContext(String summary, String toc, Map<String, String> glossary, List<String> boilerplatePatterns) {}
    public record TranslationResult(String message) {}

    @State
    public interface Stage {}

    @Action
    public DocSummaryAgent.DocSummaryRequest start(TranslateStartRequest req, ActionContext ctx) throws IOException {
        Path wsPath = workspace.initWorkspace(req.workspaceName());
        
        TranslateWorkspace.TranslateState state = new TranslateWorkspace.TranslateState();
        state.setOriginalPdfPath(req.pdfPath());
        state.setCurrentPhase(TranslateWorkspace.TranslateState.Phase.CONTEXT_EXTRACTION);
        workspace.saveState(wsPath, state);

        System.out.println("Ingesting document into in-memory RAG for glossary context...");
        localRagTools.ingestUrlToMemory(req.pdfPath(), req.workspaceName());

        System.out.println("Initiating glossary generation via DocSummaryAgent subagent (Target: 100 terms)...");
        
        return new DocSummaryAgent.DocSummaryRequest(
            Path.of(req.pdfPath()),
            req.workspaceName(),
            100 
        );
    }

    @Action
    public DocSummaryAgent.DocSummaryResult runDocSummarySubagent(DocSummaryAgent.DocSummaryRequest req) {
        return RunSubagent.fromAnnotatedInstance(
            docSummaryAgent,
            DocSummaryAgent.DocSummaryResult.class
        );
    }

    @Action
    public Stage handleDocSummaryResult(DocSummaryAgent.DocSummaryResult result, TranslateStartRequest startReq, Ai ai) throws IOException {
        Path wsPath = workspace.initWorkspace(startReq.workspaceName());
        TranslateWorkspace.TranslateState state = workspace.loadState(wsPath);
        
        System.out.println("Processing DocSummaryAgent result and preparing translation chunks...");
        
        // 1. Prepare glossary
        List<String> termStrings = result.terms().stream()
                .map(t -> String.format("%s (%s)", t.term(), t.definition()))
                .collect(Collectors.toList());
        String formattedGlossary = String.join("; ", termStrings);

        String prompt = promptProvider.getPrompt("agents/translate/extract-context.jinja", Map.of(
            "summary", result.summary(),
            "glossary", formattedGlossary
        ));

        DocumentContext partialContext = ai.withLlm(LlmOptions.withLlmForRole("normal").withoutThinking())
            .creating(DocumentContext.class).fromPrompt(prompt);

        Map<String, String> glossaryMap = result.terms().stream().collect(Collectors.toMap(
                DocSummaryAgent.DefinedTerm::term,
                DocSummaryAgent.DefinedTerm::definition,
                (v1, v2) -> v1
        ));

        DocumentContext docContext = new DocumentContext(
            result.summary(),
            partialContext.toc(),
            glossaryMap,
            partialContext.boilerplatePatterns()
        );

        // 2. Save glossary for persistence
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(docContext);
        workspace.saveGlossary(wsPath, json);

        // 3. Parse full PDF and chunk elements immediately
        File pdfFile = new File(state.getOriginalPdfPath());
        System.out.println("Parsing full PDF structure...");
        Path imagesPath = wsPath.resolve(CoreWorkspaceProvider.SubCategory.ARTIFACTS.getDirName()).resolve("images");
        List<PdfParser.PdfElement> elements = parser.parsePdf(pdfFile, imagesPath);
        
        String fullMarkdown = elements.get(0).content();
        List<String> chunks = chunkElements(fullMarkdown);
        
        state.setTotalChunks(chunks.size());
        state.setCompletedChunks(0);
        state.setCurrentPhase(TranslateWorkspace.TranslateState.Phase.CHUNK_TRANSLATION);
        workspace.saveState(wsPath, state);

        for (int i = 0; i < chunks.size(); i++) {
            workspace.saveTranslatedChunk(wsPath, i + 1000, chunks.get(i));
        }

        System.out.println("Translation chunks prepared. Starting translation loop...");
        return new TranslationLoopState(wsPath, docContext, state, workspace, translatorPersona, promptProvider);
    }

    private List<String> chunkElements(String fullMarkdown) {
        List<String> chunks = new ArrayList<>();
        String[] blocks = fullMarkdown.split("\\n{2,}");
        
        StringBuilder currentChunk = new StringBuilder();
        int MAX_CHUNK_CHARS = 5000;
        
        for (String block : blocks) {
            String trimmedBlock = block.trim();
            if (trimmedBlock.isEmpty()) continue;
            
            if (currentChunk.length() + trimmedBlock.length() + 2 > MAX_CHUNK_CHARS && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
            }
            currentChunk.append(trimmedBlock).append("\n\n");
        }
        
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }
        return chunks;
    }

    @Action
    public Stage resume(TranslateResumeRequest req) {
        try {
            Path wsPath = Path.of(req.workspaceName());
            TranslateWorkspace.TranslateState state = workspace.loadState(wsPath);
            
            if (state.getCurrentPhase() == TranslateWorkspace.TranslateState.Phase.CHUNK_TRANSLATION) {
                String glossaryJson = workspace.readGlossary(wsPath);
                DocumentContext ctx = objectMapper.readValue(glossaryJson, DocumentContext.class);
                return new TranslationLoopState(wsPath, ctx, state, workspace, translatorPersona, promptProvider);
            } else if (state.getCurrentPhase() == TranslateWorkspace.TranslateState.Phase.MERGE_AND_POSTPROCESS) {
                return new MergeState(wsPath, state, workspace);
            }
            throw new IllegalStateException("Cannot resume from phase: " + state.getCurrentPhase());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @State
    public record TranslationLoopState(Path wsPath, DocumentContext context, TranslateWorkspace.TranslateState state, TranslateWorkspace workspace, RoleGoalBackstory translatorPersona, PromptProvider promptProvider) implements Stage {
        @Action(canRerun = true, clearBlackboard = true)
        public Stage processChunk(Ai ai) {
            try {
                int currentIndex = state.getCompletedChunks();
                if (currentIndex >= state.getTotalChunks()) {
                    state.setCurrentPhase(TranslateWorkspace.TranslateState.Phase.MERGE_AND_POSTPROCESS);
                    workspace.saveState(wsPath, state);
                    return new MergeState(wsPath, state, workspace);
                }

                String sourceChunk = workspace.readTranslatedChunk(wsPath, currentIndex + 1000);
                
                String previousContext = "";
                if (currentIndex > 0) {
                    String lastTranslated = workspace.readTranslatedChunk(wsPath, currentIndex - 1);
                    if (lastTranslated != null) {
                        int contextLen = 1000;
                        previousContext = lastTranslated.length() > contextLen ? 
                            lastTranslated.substring(lastTranslated.length() - contextLen) : lastTranslated;
                    }
                }

                System.out.println("Translating chunk " + (currentIndex + 1) + " of " + state.getTotalChunks() + "...");

                String prompt = promptProvider.getPrompt("agents/translate/translate-chunk.jinja", Map.of(
                    "summary", context.summary(),
                    "glossary", context.glossary(),
                    "previousContext", previousContext,
                    "sourceChunk", sourceChunk
                ));

                String translated = ai.withLlm(LlmOptions.withLlmForRole("normal").withoutThinking())
                    .withPromptContributor(translatorPersona)
                    .generateText(prompt);

                workspace.saveTranslatedChunk(wsPath, currentIndex, translated);
                
                state.setCompletedChunks(currentIndex + 1);
                workspace.saveState(wsPath, state);

                return new TranslationLoopState(wsPath, context, state, workspace, translatorPersona, promptProvider);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @State
    public record MergeState(Path wsPath, TranslateWorkspace.TranslateState state, TranslateWorkspace workspace) implements Stage {
        @Action
        @AchievesGoal(description = "Translation completely merged and saved.")
        public TranslationResult merge() {
            try {
                System.out.println("Merging chunks...");
                StringBuilder fullText = new StringBuilder();
                for (int i = 0; i < state.getTotalChunks(); i++) {
                    fullText.append(workspace.readTranslatedChunk(wsPath, i)).append("\n\n");
                }
                
                Path exportPath = wsPath.resolve(CoreWorkspaceProvider.SubCategory.EXPORT.getDirName());
                if (!Files.exists(exportPath)) Files.createDirectories(exportPath);
                File outputFile = exportPath.resolve("final_translated.md").toFile();
                java.nio.file.Files.writeString(outputFile.toPath(), fullText.toString());
                
                state.setCurrentPhase(TranslateWorkspace.TranslateState.Phase.DONE);
                workspace.saveState(wsPath, state);
                
                System.out.println("✅ Translation completed successfully: " + outputFile.getAbsolutePath());
                ApplicationContextHolder.getPublisher().publishEvent(
                    new NotificationEvent("PDF 번역 완료", "번역 및 머지가 완료되었습니다: " + outputFile.getName())
                );
                return new TranslationResult("Successfully translated and merged to " + outputFile.getAbsolutePath());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
