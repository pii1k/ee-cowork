package io.autocrypt.jwlee.cowork.weeklyreport.service;

import io.autocrypt.jwlee.cowork.weeklyreport.dto.JiraIssueInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class JiraExcelService {

    private final RestTemplate restTemplate;

    @Value("${app.jira.baseUrl}")
    private String baseUrl;

    @Value("${app.jira.projectKey:VP}")
    private String projectKey;

    @Value("${app.confluence.email}")
    private String email;

    @Value("${app.confluence.apiToken}")
    private String apiToken;

    public JiraExcelService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private HttpHeaders createAuthHeaders() {
        String auth = email + ":" + apiToken;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
        String authHeader = "Basic " + new String(encodedAuth);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    @SuppressWarnings("unchecked")
    public List<JiraIssueInfo> readIssues() {
        // 최근 4주(약 한 달) 내에 업데이트된 이슈만 조회
        String jql = String.format("project = \"%s\" AND updated >= \"-4w\" ORDER BY updated DESC", projectKey);
        
        String url = baseUrl + "/rest/api/3/search/jql";
        
        Map<String, Object> body = new HashMap<>();
        body.put("jql", jql);
        body.put("maxResults", 100);
        body.put("fields", List.of("summary", "status", "assignee", "components"));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, createAuthHeaders());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return List.of();
            }
            Map bodyResponse = response.getBody();
            if (bodyResponse == null) {
                return List.of();
            }
            List<Map<String, Object>> issues = (List<Map<String, Object>>) bodyResponse.get("issues");

            if (issues == null) {
                return List.of();
            }

            return issues.stream().map(issue -> {
                String key = (String) issue.get("key");
                Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
                String summary = (String) fields.get("summary");
                
                Map<String, Object> status = (Map<String, Object>) fields.get("status");
                String statusName = (status != null) ? (String) status.get("name") : "Unknown";
                
                Map<String, Object> assignee = (Map<String, Object>) fields.get("assignee");
                String assigneeName = (assignee != null) ? (String) assignee.get("displayName") : "Unassigned";

                List<Map<String, Object>> components = (List<Map<String, Object>>) fields.get("components");
                String componentName = (components != null && !components.isEmpty()) 
                        ? (String) components.get(0).get("name") : "Unknown";

                return new JiraIssueInfo(key, summary, assigneeName, statusName, componentName);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }
}
