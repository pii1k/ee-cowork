package io.autocrypt.jwlee.cowork.core.hitl;

/**
 * Event published when an agent reaches a state requiring human approval.
 * 
 * @param processId The ID of the AgentProcess waiting for approval
 * @param message The prompt question for the user
 * @param planDescription Details of what the agent intends to do
 */
public record ApprovalRequestedEvent(String processId, String message, String planDescription) {
}
