package io.autocrypt.jwlee.cowork.core.hitl;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.core.hitl.WaitFor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A reusable state that pauses agent execution to wait for human approval.
 * It publishes an event so the UI (Terminal or Web) can present the prompt.
 */
@State
public record CoreApprovalState(String message, String planDescription) implements BaseAgentState {
    
    @Action(description = "Wait for user to approve or reject the proposed plan.")
    public ApprovalDecision waitForUser(ActionContext ctx) {
        
        // 1. Publish the event via static holder to notify the UI
        String processId = ctx.getProcessContext().getAgentProcess().getId();
        ApplicationContextHolder.getPublisher().publishEvent(new ApprovalRequestedEvent(processId, message, planDescription));
        
        // 2. Pause execution and wait for an ApprovalDecision object to be injected into the blackboard
        return WaitFor.formSubmission("Approval Event Published", ApprovalDecision.class);
    }
}
