package io.autocrypt.jwlee.cowork.weeklyagent.dto;

import io.autocrypt.jwlee.cowork.core.dto.JiraIssueInfo;
import java.util.List;

public record JiraIssueList(List<JiraIssueInfo> issues) {}
