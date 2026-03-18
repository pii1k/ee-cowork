package io.autocrypt.jwlee.cowork.core.hitl;

/**
 * Common data structure for human decisions.
 */
public record ApprovalDecision(boolean approved, String comment) {
    public static ApprovalDecision approve() { return new ApprovalDecision(true, "Approved"); }
    public static ApprovalDecision reject(String reason) { return new ApprovalDecision(false, reason); }
}
