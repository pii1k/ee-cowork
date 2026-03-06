package io.autocrypt.jwlee.cowork.weeklyreport.service;

import io.autocrypt.jwlee.cowork.weeklyreport.dto.JiraIssueInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class JiraExcelServiceTest {

    private RestTemplate restTemplate;
    private JiraExcelService jiraService;

    @BeforeEach
    void setUp() {
        restTemplate = Mockito.mock(RestTemplate.class);
        jiraService = new JiraExcelService(restTemplate);

        ReflectionTestUtils.setField(jiraService, "baseUrl", "https://test.atlassian.net");
        ReflectionTestUtils.setField(jiraService, "projectKey", "VP");
        ReflectionTestUtils.setField(jiraService, "email", "test@test.com");
        ReflectionTestUtils.setField(jiraService, "apiToken", "fake-token");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readIssues_shouldParseJiraResponseCorrectly() {
        // Mock Jira Search Response
        Map<String, Object> issue1 = new HashMap<>();
        issue1.put("key", "VP-1");
        Map<String, Object> fields1 = new HashMap<>();
        fields1.put("summary", "Summary 1");
        Map<String, Object> status1 = new HashMap<>();
        status1.put("name", "Done");
        fields1.put("status", status1);
        Map<String, Object> assignee1 = new HashMap<>();
        assignee1.put("displayName", "User A");
        fields1.put("assignee", assignee1);
        issue1.put("fields", fields1);

        List<Map<String, Object>> issues = new ArrayList<>();
        issues.add(issue1);

        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("issues", issues);

        Mockito.when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(responseMap));

        List<JiraIssueInfo> result = jiraService.readIssues();

        assertEquals(1, result.size());
        assertEquals("VP-1", result.get(0).key());
        assertEquals("Summary 1", result.get(0).summary());
        assertEquals("Done", result.get(0).status());
        assertEquals("User A", result.get(0).assignee());
    }
}
