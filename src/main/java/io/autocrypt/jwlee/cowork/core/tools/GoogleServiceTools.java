package io.autocrypt.jwlee.cowork.core.tools;

import com.embabel.agent.api.annotation.LlmTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Automated tools for fetching data from Google services (Tasks) with Refresh Token support.
 */
@Component
public class GoogleServiceTools {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.integration.google.client-id:}")
    private String googleClientId;
    @Value("${app.integration.google.client-secret:}")
    private String googleClientSecret;
    @Value("${app.integration.google.refresh-token:}")
    private String googleRefreshToken;

    // The specific list ID for '일반'
    private static final String TASKS_LIST_ID = "MDg5NTYzNTY4NzU1MzA2NjkxMTQ6MzE3OTE3MTU2MzUzNTM3Njow";

    @LlmTool(description = "Automatically fetches the list of tasks from Google Tasks API using Refresh Token.")
    public String fetchGoogleTasks() {
        String accessToken = refreshGoogleToken();
        if (accessToken.startsWith("ERROR")) return accessToken;
        
        try {
            // Fetch from the specific list ID
            String url = "https://tasks.googleapis.com/tasks/v1/lists/" + TASKS_LIST_ID + "/tasks";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return parseTasksToMarkdown(response.getBody());
        } catch (Exception e) {
            return "ERROR fetching Google Tasks: " + e.getMessage();
        }
    }

    private String parseTasksToMarkdown(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                return "No tasks found.";
            }

            List<String> taskTitles = new ArrayList<>();
            for (JsonNode item : items) {
                if (item.has("title") && "needsAction".equals(item.get("status").asText())) {
                    taskTitles.add("- " + item.get("title").asText());
                }
            }
            
            if (taskTitles.isEmpty()) {
                return "No active tasks found.";
            }
            return String.join("\n", taskTitles);
        } catch (Exception e) {
            return "ERROR parsing Google Tasks JSON: " + e.getMessage();
        }
    }

    private String refreshGoogleToken() {
        if (googleRefreshToken.isEmpty()) return "ERROR: GOOGLE_REFRESH_TOKEN is not set.";
        try {
            String url = "https://oauth2.googleapis.com/token";
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", googleClientId);
            body.add("client_secret", googleClientSecret);
            body.add("refresh_token", googleRefreshToken);
            body.add("grant_type", "refresh_token");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            if (response.getBody() == null || !response.getBody().containsKey("access_token")) {
                return "ERROR: access_token not found in response.";
            }
            return (String) response.getBody().get("access_token");
        } catch (Exception e) {
            return "ERROR refreshing Google Token: " + e.getMessage();
        }
    }
}
