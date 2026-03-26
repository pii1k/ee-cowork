package io.autocrypt.jwlee.cowork.core.commands;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.Verbosity;

import java.util.Collections;
import java.util.concurrent.ExecutionException;

/**
 * Base class for Spring Shell commands that invoke Embabel agents.
 */
public abstract class BaseAgentCommand {

    protected final AgentPlatform agentPlatform;

    protected BaseAgentCommand(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    /**
     * Helper to create ProcessOptions with verbosity settings.
     */
    protected ProcessOptions getOptions(boolean showPrompts, boolean showResponses) {
        return ProcessOptions.DEFAULT.withVerbosity(new Verbosity()
                .withShowPrompts(showPrompts)
                .withShowLlmResponses(showResponses));
    }

    /**
     * Helper to invoke an agent with consistent process handling and options.
     * Explicitly handles 0-10 arguments to prevent Java varargs wrapping issues.
     */
    protected <T> T invokeAgent(Class<T> resultType, ProcessOptions options, Object... requests) 
            throws ExecutionException, InterruptedException {
        
        var invocation = AgentInvocation.create(agentPlatform, resultType)
                .withProcessOptions(options);

        AgentProcess process;
        int len = (requests == null) ? 0 : requests.length;

        process = switch (len) {
            case 0 -> invocation.runAsync(Collections.emptyMap()).get();
            case 1 -> invocation.runAsync(requests[0]).get();
            case 2 -> invocation.runAsync(requests[0], requests[1]).get();
            case 3 -> invocation.runAsync(requests[0], requests[1], requests[2]).get();
            case 4 -> invocation.runAsync(requests[0], requests[1], requests[2], requests[3]).get();
            case 5 -> invocation.runAsync(requests[0], requests[1], requests[2], requests[3], requests[4]).get();
            case 6 -> invocation.runAsync(requests[0], requests[1], requests[2], requests[3], requests[4], requests[5]).get();
            case 7 -> invocation.runAsync(requests[0], requests[1], requests[2], requests[3], requests[4], requests[5], requests[6]).get();
            case 8 -> invocation.runAsync(requests[0], requests[1], requests[2], requests[3], requests[4], requests[5], requests[6], requests[7]).get();
            case 9 -> invocation.runAsync(requests[0], requests[1], requests[2], requests[3], requests[4], requests[5], requests[6], requests[7], requests[8]).get();
            case 10 -> invocation.runAsync(requests[0], requests[1], requests[2], requests[3], requests[4], requests[5], requests[6], requests[7], requests[8], requests[9]).get();
            default -> throw new IllegalArgumentException("에이전트 인자가 너무 많습니다 (최대 10개). 현재 인자 수: " + len);
        };

        while (!process.getFinished()) {
            Thread.sleep(500);
        }

        return process.resultOfType(resultType);
    }
}
