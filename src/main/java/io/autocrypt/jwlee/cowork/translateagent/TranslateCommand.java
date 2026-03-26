package io.autocrypt.jwlee.cowork.translateagent;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.EarlyTerminationPolicy;
import com.embabel.agent.core.ProcessOptions;
import io.autocrypt.jwlee.cowork.core.commands.BaseAgentCommand;
import io.autocrypt.jwlee.cowork.core.hitl.ApplicationContextHolder;
import io.autocrypt.jwlee.cowork.core.hitl.NotificationEvent;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.File;
import java.util.concurrent.ExecutionException;

@ShellComponent
public class TranslateCommand extends BaseAgentCommand {

    public TranslateCommand(AgentPlatform agentPlatform) {
        super(agentPlatform);
    }

    private ProcessOptions getTranslateOptions(boolean p, boolean r) {
        ProcessOptions options = getOptions(p, r);
        return options.withAdditionalEarlyTerminationPolicy(EarlyTerminationPolicy.maxActions(500));
    }

    private String handleProcessCompletion(TranslateAgent.TranslationResult result) {
        if (result == null) {
            String errorMsg = "번역 프로세스 결과가 없거나 비정상 종료되었습니다.";
            ApplicationContextHolder.getPublisher().publishEvent(new NotificationEvent("번역 실패", errorMsg));
            return "Error: " + errorMsg;
        }

        return result.message();
    }

    @ShellMethod(value = "Start a new translation task.", key = "translate start")
    public String start(
            @ShellOption(help = "Path to the PDF file") String pdf,
            @ShellOption(defaultValue = ".translate_workspace", help = "Workspace directory name") String workspace,
            @ShellOption(value = {"-p", "--show-prompts"}, defaultValue = "false", help = "Log prompts sent to the LLM") boolean p,
            @ShellOption(value = {"-r", "--show-responses"}, defaultValue = "false", help = "Log LLM responses") boolean r) throws ExecutionException, InterruptedException {

        if (!new File(pdf).exists()) {
            return "Error: PDF file not found at " + pdf;
        }

        System.out.println("Starting translation process for " + pdf + " in workspace " + workspace);

        TranslateAgent.TranslationResult result = invokeAgent(
                TranslateAgent.TranslationResult.class,
                getTranslateOptions(p, r),
                new TranslateAgent.TranslateStartRequest(pdf, workspace)
        );

        return handleProcessCompletion(result);
    }

    @ShellMethod(value = "Resume an existing translation task.", key = "translate resume")
    public String resume(
            @ShellOption(defaultValue = ".translate_workspace", help = "Workspace directory name") String workspace,
            @ShellOption(value = {"-p", "--show-prompts"}, defaultValue = "false", help = "Log prompts sent to the LLM") boolean p,
            @ShellOption(value = {"-r", "--show-responses"}, defaultValue = "false", help = "Log LLM responses") boolean r) throws ExecutionException, InterruptedException {

        File wsDir = new File(workspace);
        if (!wsDir.exists()) {
            return "Error: Workspace not found at " + workspace;
        }

        System.out.println("Resuming translation process from workspace " + workspace);

        TranslateAgent.TranslationResult result = invokeAgent(
                TranslateAgent.TranslationResult.class,
                getTranslateOptions(p, r),
                new TranslateAgent.TranslateResumeRequest(workspace)
        );

        return handleProcessCompletion(result);
    }
}
