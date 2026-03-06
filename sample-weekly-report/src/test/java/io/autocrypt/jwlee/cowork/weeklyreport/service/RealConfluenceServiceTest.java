package io.autocrypt.jwlee.cowork.weeklyreport.service;

import io.autocrypt.jwlee.cowork.weeklyreport.dto.OkrInfo;
import io.autocrypt.jwlee.cowork.weeklyreport.dto.TeamReportInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class RealConfluenceServiceTest {

    private RestTemplate restTemplate;
    private RealConfluenceService confluenceService;

    @BeforeEach
    void setUp() {
        restTemplate = Mockito.mock(RestTemplate.class);
        confluenceService = new RealConfluenceService(restTemplate);
        
        ReflectionTestUtils.setField(confluenceService, "baseUrl", "https://test.atlassian.net/wiki");
        ReflectionTestUtils.setField(confluenceService, "email", "test@test.com");
        ReflectionTestUtils.setField(confluenceService, "apiToken", "fake-token");
        ReflectionTestUtils.setField(confluenceService, "okrPageId", "123");
        ReflectionTestUtils.setField(confluenceService, "meetingRootId", "456");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getOkr_shouldParseHtmlCorrectly() {
        // Mock Response
        Map<String, Object> bodyMap = new HashMap<>();
        Map<String, Object> storageMap = new HashMap<>();
        storageMap.put("value", "<ul><li>OKR 1</li><li>OKR 2</li></ul>");
        bodyMap.put("storage", storageMap);
        
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("body", bodyMap);
        
        Mockito.when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(responseMap));

        OkrInfo okr = confluenceService.getOkr();

        assertNotNull(okr);
        assertEquals(2, okr.objectives().size());
        assertTrue(okr.objectives().contains("OKR 1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTeamReports_shouldParseHeadersAndContent() {
        // Mock Response with H2 and UL
        Map<String, Object> bodyMap = new HashMap<>();
        Map<String, Object> storageMap = new HashMap<>();
        storageMap.put("value", "<h2>Team A</h2><ul><li>Task 1</li></ul><h2>Team B</h2><p>Task 2</p>");
        bodyMap.put("storage", storageMap);
        
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("body", bodyMap);
        
        Mockito.when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(responseMap));

        List<TeamReportInfo> reports = confluenceService.getTeamReports("789");

        assertEquals(2, reports.size());
        assertEquals("Team A", reports.get(0).teamName());
        assertTrue(reports.get(0).content().contains("Task 1"));
        assertEquals("Team B", reports.get(1).teamName());
        assertTrue(reports.get(1).content().contains("Task 2"));
    }
}
