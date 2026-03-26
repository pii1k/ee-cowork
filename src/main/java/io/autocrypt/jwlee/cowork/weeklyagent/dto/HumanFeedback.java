package io.autocrypt.jwlee.cowork.weeklyagent.dto;

/**
 * 인간의 승인/반려 피드백
 */
public record HumanFeedback(
    boolean approved,
    String comments
) {}
