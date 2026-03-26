package io.autocrypt.jwlee.cowork.docsummaryagent;

import com.embabel.agent.core.AgentPlatform;
import io.autocrypt.jwlee.cowork.core.commands.BaseAgentCommand;
import io.autocrypt.jwlee.cowork.core.tools.LocalRagTools;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;

@ShellComponent
public class AnkiCommand extends BaseAgentCommand {

    private final LocalRagTools localRagTools;
    private final AnkiService ankiService;

    public AnkiCommand(AgentPlatform agentPlatform, LocalRagTools localRagTools, AnkiService ankiService) {
        super(agentPlatform);
        this.localRagTools = localRagTools;
        this.ankiService = ankiService;
    }

    @ShellMethod(value = "Generate Anki cards from a document (Full Process)", key = "anki-gen")
    public String generateAnki(
            @ShellOption(help = "Path to the document (PDF or Markdown)") String filePath,
            @ShellOption(help = "Workspace/Deck name", defaultValue = "anki_default") String wsName,
            @ShellOption(help = "Number of final cards to keep", defaultValue = "30") int maxCards,
            @ShellOption(value = {"-p", "--show-prompts"}, defaultValue = "false", help = "Log prompts sent to the LLM") boolean p,
            @ShellOption(value = {"-r", "--show-responses"}, defaultValue = "false", help = "Log LLM responses") boolean r) throws ExecutionException, InterruptedException, IOException {
        
        Path path = Paths.get(filePath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            return "[Error] File not found: " + path;
        }

        System.out.println("[System] Ingesting document into in-memory RAG for context...");
        String ingestResult = localRagTools.ingestUrlToMemory(path.toString(), wsName);
        if (ingestResult.startsWith("ERROR")) {
            return "[Error] RAG Ingestion failed: " + ingestResult;
        }

        DocSummaryAgent.DocSummaryRequest request = new DocSummaryAgent.DocSummaryRequest(path, wsName, maxCards);

        System.out.println("[System] Starting Document Summary Agent for: " + filePath);

        DocSummaryAgent.DocSummaryResult result = invokeAgent(
                DocSummaryAgent.DocSummaryResult.class,
                getOptions(p, r),
                request
        );

        if (result != null) {
            String csvPath = ankiService.generateAnkiCsv(wsName, result.terms());
            return "[System] Anki CSV generated: " + csvPath;
        } else {
            return "[System] Anki generation failed or was interrupted.";
        }
    }

    @ShellMethod(value = "Resume Anki card generation from filtering phase", key = "anki-resume")
    public String resumeAnki(
            @ShellOption(help = "Workspace/Deck name") String wsName,
            @ShellOption(help = "Number of final cards to keep", defaultValue = "30") int maxCards,
            @ShellOption(value = {"-p", "--show-prompts"}, defaultValue = "false", help = "Log prompts sent to the LLM") boolean p,
            @ShellOption(value = {"-r", "--show-responses"}, defaultValue = "false", help = "Log LLM responses") boolean r) throws ExecutionException, InterruptedException, IOException {

        DocSummaryAgent.DocSummaryResumeRequest request = new DocSummaryAgent.DocSummaryResumeRequest(wsName, maxCards);

        System.out.println("[System] Resuming Anki Generation for workspace: " + wsName);

        DocSummaryAgent.DocSummaryResult result = invokeAgent(
                DocSummaryAgent.DocSummaryResult.class,
                getOptions(p, r),
                request
        );

        if (result != null) {
            String csvPath = ankiService.generateAnkiCsv(wsName, result.terms());
            return "[System] Anki CSV regenerated: " + csvPath;
        } else {
            return "[System] Anki resume failed or was interrupted.";
        }
    }
}
