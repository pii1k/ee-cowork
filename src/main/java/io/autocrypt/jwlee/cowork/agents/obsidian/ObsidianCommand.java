package io.autocrypt.jwlee.cowork.agents.obsidian;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

import java.util.concurrent.ExecutionException;

@ShellComponent
public class ObsidianCommand {

    private final AgentPlatform agentPlatform;

    public ObsidianCommand(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    @ShellMethod(key = "obsidian-daily", value = "Create a new daily note in Obsidian vault")
    public String daily() throws ExecutionException, InterruptedException {
        System.out.println("[System] Starting Obsidian Daily Note Agent...");
        AgentProcess process = AgentInvocation
                .create(agentPlatform, ObsidianAgent.ObsidianResult.class)
                .runAsync(new ObsidianAgent.DailyRequest())
                .get();

        while (!process.getFinished()) {
            Thread.sleep(500);
        }

        ObsidianAgent.ObsidianResult result = process.resultOfType(ObsidianAgent.ObsidianResult.class);
        return result != null ? result.message() : "Failed to generate daily note.";
    }

    @ShellMethod(key = "obsidian-weekly", value = "Create a new weekly note and cleanup daily notes")
    public String weekly() throws ExecutionException, InterruptedException {
        System.out.println("[System] Starting Obsidian Weekly Note Agent...");
        AgentProcess process = AgentInvocation
                .create(agentPlatform, ObsidianAgent.ObsidianResult.class)
                .runAsync(new ObsidianAgent.WeeklyRequest())
                .get();

        while (!process.getFinished()) {
            Thread.sleep(500);
        }

        ObsidianAgent.ObsidianResult result = process.resultOfType(ObsidianAgent.ObsidianResult.class);
        return result != null ? result.message() : "Failed to generate weekly note.";
    }
}
