package io.autocrypt.jwlee.cowork.weeklyreport.service;

import io.autocrypt.jwlee.cowork.weeklyreport.dto.JiraIssueInfo;
import io.autocrypt.jwlee.cowork.weeklyreport.dto.OkrInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataIntegrationTest {

    private RealConfluenceService confluenceService;
    private JiraExcelService jiraService;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        confluenceService = new RealConfluenceService(restTemplate);
        jiraService = new JiraExcelService(restTemplate);

        // 환경 변수에서 값 로드
        String apiToken = System.getenv("CONFLUENCE_API_TOKEN");
        
        ReflectionTestUtils.setField(confluenceService, "baseUrl", "https://auto-jira.atlassian.net/wiki");
        ReflectionTestUtils.setField(confluenceService, "email", "jwlee@autocrypt.io");
        ReflectionTestUtils.setField(confluenceService, "apiToken", apiToken);
        ReflectionTestUtils.setField(confluenceService, "okrPageId", "2781544496");
        ReflectionTestUtils.setField(confluenceService, "meetingRootId", "1778647765");

        ReflectionTestUtils.setField(jiraService, "baseUrl", "https://auto-jira.atlassian.net");
        ReflectionTestUtils.setField(jiraService, "projectKey", "VP");
        ReflectionTestUtils.setField(jiraService, "email", "jwlee@autocrypt.io");
        ReflectionTestUtils.setField(jiraService, "apiToken", apiToken);
    }

    @Test
    void testRealConfluenceOkr() {
        OkrInfo okr = confluenceService.getOkr();
        System.out.println("=== REAL OKR DATA ===");
        System.out.println("Quarter: " + okr.quarter());
        okr.objectives().forEach(o -> System.out.println(" - " + o));
        assertNotEquals("Error", okr.quarter());
    }

    @Test
    void testRealJiraIssues() {
        List<JiraIssueInfo> issues = jiraService.readIssues();
        System.out.println("=== REAL JIRA DATA (Recent 10 issues) ===");
        issues.stream().limit(10).forEach(issue -> {
            System.out.println(String.format("[%s] %s (%s) - %s", 
                issue.key(), issue.summary(), issue.assignee(), issue.status()));
        });
        assertFalse(issues.isEmpty(), "Jira에서 이슈를 하나도 가져오지 못했습니다. JQL이나 권한을 확인하세요.");
    }
}
