package io.autocrypt.jwlee.cowork.web.core.prompts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import com.hubspot.jinjava.Jinjava;
import com.embabel.agent.prompt.persona.RoleGoalBackstory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Service
public class PromptProvider {

    private static final Logger log = LoggerFactory.getLogger(PromptProvider.class);
    private final Jinjava jinjava = new Jinjava();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    private static final String PROMPT_BASE_PATH = "prompts/";

    public String getPrompt(String path, Map<String, Object> params) {
        String fullPath = PROMPT_BASE_PATH + path;
        try {
            ClassPathResource resource = new ClassPathResource(fullPath);
            if (!resource.exists()) {
                log.error("Prompt resource not found: {}", fullPath);
                throw new RuntimeException("Prompt not found: " + fullPath);
            }
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            if (path.endsWith(".jinja")) {
                Map<String, Object> safeParams = convertRecordsToMaps(params);
                return jinjava.render(content, safeParams != null ? safeParams : Collections.emptyMap());
            }
            return content;
        } catch (IOException e) {
            log.error("Failed to load prompt from path: {}", fullPath, e);
            throw new RuntimeException("Prompt not found: " + fullPath, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertRecordsToMaps(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return params;
        try {
            return objectMapper.convertValue(params, Map.class);
        } catch (Exception e) {
            log.warn("Failed to pre-process prompt params for Jinjava, using original params", e);
            return params;
        }
    }

    public String getPrompt(String path) {
        return getPrompt(path, Collections.emptyMap());
    }

    public RoleGoalBackstory getPersona(String path, Map<String, Object> params) {
        String content = getPrompt(path, params);
        String role = extractSection(content, "ROLE");
        String goal = extractSection(content, "GOAL");
        String backstory = extractSection(content, "BACKSTORY");
        return new RoleGoalBackstory(role, goal, backstory);
    }

    public RoleGoalBackstory getPersona(String path) {
        return getPersona(path, Collections.emptyMap());
    }

    private String extractSection(String content, String sectionName) {
        Pattern pattern = Pattern.compile(
            "(?m)^#+\\s*" + sectionName + "\\s*$\\n?([\\s\\S]*?)(?=\\n#+|$)"
        );
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }
}
