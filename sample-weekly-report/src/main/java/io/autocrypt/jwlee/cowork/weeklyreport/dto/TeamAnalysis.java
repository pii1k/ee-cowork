package io.autocrypt.jwlee.cowork.weeklyreport.dto;

public record TeamAnalysis(
    String teamName,
    String currentOkr,
    String currentMeetingIssues,
    String currentJiraIssues,
    String aiOpinion
) {}