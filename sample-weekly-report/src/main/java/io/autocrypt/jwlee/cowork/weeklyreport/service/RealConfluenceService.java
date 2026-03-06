package io.autocrypt.jwlee.cowork.weeklyreport.service;

import io.autocrypt.jwlee.cowork.weeklyreport.dto.MeetingInfo;
import io.autocrypt.jwlee.cowork.weeklyreport.dto.OkrInfo;
import io.autocrypt.jwlee.cowork.weeklyreport.dto.TeamReportInfo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Primary
public class RealConfluenceService implements ConfluenceService {

    private final RestTemplate restTemplate;

    @Value("${app.confluence.baseUrl}")
    private String baseUrl;

    @Value("${app.confluence.email}")
    private String email;

    @Value("${app.confluence.apiToken}")
    private String apiToken;

    @Value("${app.confluence.okr-page-id}")
    private String okrPageId;

    @Value("${app.confluence.meeting-root-id}")
    private String meetingRootId;

    public RealConfluenceService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private HttpHeaders createAuthHeaders() {
        String auth = email + ":" + apiToken;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
        String authHeader = "Basic " + new String(encodedAuth);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        return headers;
    }

    @Override
    @SuppressWarnings("unchecked")
    public OkrInfo getOkr() {
        if (okrPageId == null || okrPageId.isBlank()) {
            return new OkrInfo("N/A", List.of("OKR Page ID is not configured."), "N/A");
        }
        
        String url = baseUrl + "/api/v2/pages/" + okrPageId + "?body-format=storage";
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders());
        
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) return new OkrInfo("N/A", List.of(), "Empty Response");
            
            String title = (String) responseBody.get("title");
            Map<String, Object> body = (Map<String, Object>) responseBody.get("body");
            String htmlContent = (String) ((Map<String, Object>) body.get("storage")).get("value");
            
            Document doc = Jsoup.parse(htmlContent);
            List<String> contents = new ArrayList<>();
            
            doc.select("li").forEach(li -> contents.add("• " + li.text()));
            
            Elements tables = doc.select("table");
            for (Element table : tables) {
                StringBuilder tableText = new StringBuilder("\n[Table Data]\n");
                Elements rows = table.select("tr");
                for (Element row : rows) {
                    String rowText = row.select("th, td").stream()
                            .map(Element::text)
                            .collect(Collectors.joining(" | "));
                    tableText.append(rowText).append("\n");
                }
                contents.add(tableText.toString());
            }
            
            return new OkrInfo("Current Quarter", contents, title);
        } catch (Exception e) {
            return new OkrInfo("Error", List.of("Failed to fetch OKR: " + e.getMessage()), "Error");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<TeamReportInfo> getTeamReports(String meetingPageId) {
        String url = baseUrl + "/api/v2/pages/" + meetingPageId + "?body-format=storage";
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = (Map<String, Object>) response.getBody().get("body");
            String htmlContent = (String) ((Map<String, Object>) body.get("storage")).get("value");

            Document doc = Jsoup.parse(htmlContent);
            // 전체 텍스트를 그대로 가져오기
            String fullText = doc.body().text();
            
            // AI가 파싱하기 쉽도록 전체 텍스트를 하나의 리포트로 전달
            return List.of(new TeamReportInfo("ALL_TEAMS", fullText));
        } catch (Exception e) {
            return List.of(new TeamReportInfo("Error", "Failed to fetch meeting: " + e.getMessage()));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<MeetingInfo> getRecentMeetingUrls() {
        if (meetingRootId == null || meetingRootId.isBlank()) {
            return List.of();
        }
        
        String url = baseUrl + "/api/v2/pages/" + meetingRootId + "/children";
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) return List.of();
            
            List<Map<String, Object>> results = (List<Map<String, Object>>) responseBody.get("results");
            if (results == null) return List.of();
            
            List<MeetingInfo> meetings = results.stream()
                    .map(m -> new MeetingInfo((String) m.get("id"), (String) m.get("title")))
                    .collect(Collectors.toList());
            java.util.Collections.reverse(meetings);
            return meetings;
        } catch (Exception e) {
            System.err.println("Failed to fetch child pages from parent ID " + meetingRootId + ": " + e.getMessage());
            return List.of();
        }
    }
}
