package io.autocrypt.jwlee.cowork.opsagent;

import com.embabel.agent.core.AgentPlatform;
import io.autocrypt.jwlee.cowork.core.commands.BaseAgentCommand;
import io.autocrypt.jwlee.cowork.opsagent.domain.OpsRequest;
import io.autocrypt.jwlee.cowork.opsagent.domain.OpsResult;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.concurrent.ExecutionException;

/**
 * CLI Command for OpsAgent.
 */
@ShellComponent
public class OpsAgentCommand extends BaseAgentCommand {

    public OpsAgentCommand(AgentPlatform agentPlatform) {
        super(agentPlatform);
    }

    @ShellMethod(value = "Analyze infrastructure and operational environment of the repository.", key = "ops-analyze")
    public String analyzeOps(
            @ShellOption(help = "Root path of the project to analyze.", defaultValue = ".") String path,
            @ShellOption(help = "Optional context for analysis.", defaultValue = "") String context,
            @ShellOption(help = "Show LLM prompts.", defaultValue = "false") boolean showPrompts,
            @ShellOption(help = "Show LLM responses.", defaultValue = "false") boolean showResponses
    ) throws ExecutionException, InterruptedException {
        
        logger.info("OpsCommand", "Starting Operations analysis for path: " + path);
        
        OpsRequest request = new OpsRequest(path, context);
        OpsResult result = invokeAgent(OpsResult.class, getOptions(showPrompts, showResponses), request);
        
        return result.report();
    }
}
