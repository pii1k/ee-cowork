package io.autocrypt.jwlee.cowork.core.tools;

import io.autocrypt.jwlee.cowork.core.dto.MeetingInfo;
import io.autocrypt.jwlee.cowork.core.dto.OkrInfo;
import io.autocrypt.jwlee.cowork.core.dto.TeamReportInfo;
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

    @Value("${app.confluence.baseUrl:https://auto-jira.atlassian.net/wiki}")
    private String baseUrl;

    @Value("${app.confluence.email:jwlee@autocrypt.io}")
    private String email;

    @Value("${app.confluence.apiToken:}")
    private String apiToken;

    @Value("${app.confluence.okr-page-id:2781544496}")
    private String okrPageId;

    @Value("${app.confluence.meeting-root-id:1778647765}")
    private String meetingRootId;

    public RealConfluenceService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String getOkrPageId() {
        return okrPageId;
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
            Map<String, Object> storage = (Map<String, Object>) body.get("storage");
            String html = (String) storage.get("value");

            Document doc = Jsoup.parse(html);
            List<String> objectives = doc.select("ul li").stream()
                    .map(Element::text)
                    .collect(Collectors.toList());

            return new OkrInfo("Current", objectives, title);
        } catch (Exception e) {
            return new OkrInfo("Error", List.of(e.getMessage()), "Error");
        }
    }

    @Override
    public List<TeamReportInfo> getTeamReports(String meetingUrl) {
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<MeetingInfo> getRecentMeetingUrls() {
        String url = baseUrl + "/api/v2/pages/" + meetingRootId + "/children";
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) return List.of();

            List<Map<String, Object>> results = (List<Map<String, Object>>) responseBody.get("results");
            if (results == null) return List.of();

            return results.stream()
                    .map(r -> new MeetingInfo((String) r.get("id"), (String) r.get("title")))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String getPageStorage(String pageId) {
        String url = baseUrl + "/api/v2/pages/" + pageId + "?body-format=storage";
        HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null) return "";

            Map<String, Object> body = (Map<String, Object>) responseBody.get("body");
            Map<String, Object> storage = (Map<String, Object>) body.get("storage");
            return (String) storage.get("value");
        } catch (Exception e) {
            return "";
        }
    }
}
