package io.autocrypt.jwlee.cowork.scaffoldagent;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import io.autocrypt.jwlee.cowork.core.commands.BaseAgentCommand;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.concurrent.ExecutionException;

@ShellComponent
public class ScaffoldCommand extends BaseAgentCommand {

    public ScaffoldCommand(AgentPlatform agentPlatform) {
        super(agentPlatform);
    }

    @ShellMethod(key = "scaffold-start", value = "Scaffold a new agent process")
    public String start(
            @ShellOption(help = "Input parameter for the agent") String input,
            @ShellOption(value = {"-p", "--show-prompts"}, defaultValue = "false", help = "Log prompts sent to the LLM") boolean p,
            @ShellOption(value = {"-r", "--show-responses"}, defaultValue = "false", help = "Log LLM responses") boolean r
    ) throws ExecutionException, InterruptedException {
        System.out.println("[System] Starting Scaffold Agent...");
        
        ScaffoldAgent.ScaffoldResult result = invokeAgent(
                ScaffoldAgent.ScaffoldResult.class, 
                getOptions(p, r),
                new UserInput(input));

        return result != null ? result.finalOutput() : "Failed to execute scaffold agent.";
    }
}
