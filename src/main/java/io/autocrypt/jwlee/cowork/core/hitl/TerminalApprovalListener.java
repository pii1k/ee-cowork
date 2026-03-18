package io.autocrypt.jwlee.cowork.core.hitl;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.chat.ChatSession;
import com.embabel.chat.UserMessage;
import org.jline.terminal.Terminal;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.Async;
import org.springframework.shell.component.ConfirmationInput;
import org.springframework.shell.component.StringInput;
import org.springframework.shell.style.TemplateExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Central listener for approval events.
 * It handles terminal UI locking to prevent overlapping prompts from multiple agents.
 */
@Component
public class TerminalApprovalListener {

    private final Terminal terminal;
    private final ResourceLoader resourceLoader;
    private final TemplateExecutor templateExecutor;
    private final AgentPlatform platform;
    // For single-user CLI, we assume there's one primary chat session. 
    // In a web context, you'd map processId to a specific websocket session.
    private ChatSession currentSession;

    public TerminalApprovalListener(Terminal terminal, ResourceLoader resourceLoader, 
                                    TemplateExecutor templateExecutor, AgentPlatform platform) {
        this.terminal = terminal;
        this.resourceLoader = resourceLoader;
        this.templateExecutor = templateExecutor;
        this.platform = platform;
    }

    // Required to signal the agent to wake up after injecting data
    public void setCurrentSession(ChatSession session) {
        this.currentSession = session;
    }

    /**
     * Listens for ApprovalRequestedEvent from any agent in the system.
     * Uses synchronized to lock the terminal UI if multiple agents request approval simultaneously.
     * Executed asynchronously so the main agent process thread is not blocked waiting for UI rendering.
     */
    @Async
    @EventListener
    public synchronized void onApprovalRequested(ApprovalRequestedEvent event) {
        AgentProcess process = platform.getAgentProcess(event.processId());
        
        // Wait briefly to ensure the agent has fully transitioned to WAITING state
        int retries = 0;
        while (process.getStatus() != AgentProcessStatusCode.WAITING && retries < 50) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            retries++;
        }

        terminal.writer().println("\n========================================");
        terminal.writer().println("[!] Agent Approval Required");
        terminal.writer().println("Message: " + event.message());
        terminal.writer().println("Plan: " + event.planDescription());
        terminal.writer().println("========================================");
        terminal.writer().flush();

        // 1. Show Y/N confirmation using Spring Shell UI
        ConfirmationInput input = new ConfirmationInput(terminal, "Approve this plan?", true);
        input.setResourceLoader(resourceLoader);
        input.setTemplateExecutor(templateExecutor);
        
        Boolean approved = input.run(ConfirmationInput.ConfirmationInputContext.empty()).getResultValue();

        String comment = "Approved via shell";
        if (Boolean.FALSE.equals(approved)) {
            // 2. If rejected, ask for a reason/comment
            StringInput commentInput = new StringInput(terminal, "Reason for rejection: ", "No comment");
            commentInput.setResourceLoader(resourceLoader);
            commentInput.setTemplateExecutor(templateExecutor);
            comment = commentInput.run(StringInput.StringInputContext.empty()).getResultValue();
        }

        // 3. Inject the decision directly into the waiting agent's blackboard
        process.getBlackboard().addObject(new ApprovalDecision(approved != null && approved, comment));
        
        // 4. Send a signal (UserMessage) to the session or explicitly run the process to wake up the planner
        if (currentSession != null) {
            currentSession.onUserMessage(new UserMessage("Decision submitted: " + approved, "system", Instant.now()));
        } else {
            // If no chat session is active (like in our demo command), directly resume the process
            process.run();
        }
    }
}
