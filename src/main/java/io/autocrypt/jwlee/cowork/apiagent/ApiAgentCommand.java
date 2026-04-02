package io.autocrypt.jwlee.cowork.apiagent;

import com.embabel.agent.core.AgentPlatform;
import io.autocrypt.jwlee.cowork.apiagent.domain.ApiRequest;
import io.autocrypt.jwlee.cowork.apiagent.domain.ApiResult;
import io.autocrypt.jwlee.cowork.core.commands.BaseAgentCommand;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.concurrent.ExecutionException;

/**
 * CLI Command for ApiAgent.
 */
@ShellComponent
public class ApiAgentCommand extends BaseAgentCommand {

    public ApiAgentCommand(AgentPlatform agentPlatform) {
        super(agentPlatform);
    }

    @ShellMethod(value = "Analyze API endpoints in the codebase.", key = "api-analyze")
    public String analyzeApi(
            @ShellOption(help = "Root path of the project to analyze.", defaultValue = ".") String path,
            @ShellOption(help = "Optional context for analysis.", defaultValue = "") String context,
            @ShellOption(help = "Show LLM prompts.", defaultValue = "false") boolean showPrompts,
            @ShellOption(help = "Show LLM responses.", defaultValue = "false") boolean showResponses
    ) throws ExecutionException, InterruptedException {
        
        logger.info("ApiCommand", "Starting API analysis for path: " + path);
        
        ApiRequest request = new ApiRequest(path, context);
        ApiResult result = invokeAgent(ApiResult.class, getOptions(showPrompts, showResponses), request);
        
        return result.report();
    }
}
