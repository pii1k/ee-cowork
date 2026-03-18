package io.autocrypt.jwlee.cowork.agents.weekly.dto;

public record TeamAnalysis(
    String teamName,
    String currentOkr,
    String currentMeetingIssues,
    String currentJiraIssues,
    String aiOpinion
) {}