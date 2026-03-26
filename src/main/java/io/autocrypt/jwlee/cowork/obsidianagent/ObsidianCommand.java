package io.autocrypt.jwlee.cowork.obsidianagent;

import com.embabel.agent.core.AgentPlatform;
import io.autocrypt.jwlee.cowork.core.commands.BaseAgentCommand;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.concurrent.ExecutionException;

@ShellComponent
public class ObsidianCommand extends BaseAgentCommand {

    public ObsidianCommand(AgentPlatform agentPlatform) {
        super(agentPlatform);
    }

    @ShellMethod(key = "obsidian-daily", value = "Create a new daily note in Obsidian vault")
    public String daily(
            @ShellOption(value = {"-p", "--show-prompts"}, defaultValue = "false", help = "Log prompts sent to the LLM") boolean p,
            @ShellOption(value = {"-r", "--show-responses"}, defaultValue = "false", help = "Log LLM responses") boolean r
    ) throws ExecutionException, InterruptedException {
        System.out.println("[System] Starting Obsidian Daily Note Agent...");
        ObsidianAgent.ObsidianResult result = invokeAgent(
                ObsidianAgent.ObsidianResult.class, 
                getOptions(p, r),
                new ObsidianAgent.DailyRequest());
        
        return result != null ? result.message() : "Failed to generate daily note.";
    }

    @ShellMethod(key = "obsidian-weekly", value = "Create a new weekly note and cleanup daily notes")
    public String weekly(
            @ShellOption(value = {"-p", "--show-prompts"}, defaultValue = "false", help = "Log prompts sent to the LLM") boolean p,
            @ShellOption(value = {"-r", "--show-responses"}, defaultValue = "false", help = "Log LLM responses") boolean r
    ) throws ExecutionException, InterruptedException {
        System.out.println("[System] Starting Obsidian Weekly Note Agent...");
        ObsidianAgent.ObsidianResult result = invokeAgent(
                ObsidianAgent.ObsidianResult.class, 
                getOptions(p, r),
                new ObsidianAgent.WeeklyRequest());
        
        return result != null ? result.message() : "Failed to generate weekly note.";
    }
}
