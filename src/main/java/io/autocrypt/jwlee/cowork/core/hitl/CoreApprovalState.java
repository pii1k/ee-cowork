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
    public ApprovalDecision waitForUser(ActionContext ctx, ApplicationEventPublisher publisher) {
        
        // 1. Publish the event to notify the UI that approval is needed
        // Identifies the currently running agent process
        String processId = ctx.getProcessContext().getAgentProcess().getId();
        publisher.publishEvent(new ApprovalRequestedEvent(processId, message, planDescription));
        
        // 2. Pause execution and wait for an ApprovalDecision object to be injected into the blackboard
        // When the UI submits the decision, it will inject this object and wake up the agent.
        return WaitFor.formSubmission("Approval Event Published", ApprovalDecision.class);
    }
}
