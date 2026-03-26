package io.autocrypt.jwlee.cowork.core.tools;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import io.autocrypt.jwlee.cowork.core.dto.JiraIssueInfo;

@Service
public class JiraService {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String projectKey;
    private final String email;
    private final String apiToken;

    public JiraService(RestTemplate restTemplate,
                       @Value("${app.jira.baseUrl:https://auto-jira.atlassian.net}") String baseUrl,
                       @Value("${app.jira.projectKey:VP}") String projectKey,
                       @Value("${app.confluence.email:jwlee@autocrypt.io}") String email,
                       @Value("${app.confluence.apiToken:}") String apiToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.projectKey = projectKey;
        this.email = email;
        this.apiToken = apiToken;
    }

    private HttpHeaders createAuthHeaders() {
        String auth = email + ":" + apiToken;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
        String authHeader = "Basic " + new String(encodedAuth, StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    @SuppressWarnings("unchecked")
    public List<JiraIssueInfo> readIssues(String updatedSince) {
        String jql = String.format("project = \"%s\" AND updated >= \"%s\" ORDER BY updated DESC", 
                                   projectKey, updatedSince);
        
        String url = baseUrl + "/rest/api/3/search/jql";
        
        Map<String, Object> body = new HashMap<>();
        body.put("jql", jql);
        body.put("maxResults", 300);
        body.put("fields", List.of("summary", "status", "assignee", "components", "created", "updated"));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, createAuthHeaders());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            Map bodyResponse = response.getBody();
            
            if (!response.getStatusCode().is2xxSuccessful()) {
                return List.of();
            }
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
