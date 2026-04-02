package io.autocrypt.jwlee.cowork.core.prompts;

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

/**
 * PromptProvider responsible for loading and rendering prompts from resources.
 * Supports .jinja for dynamic templates and .md/.txt for static prompts.
 * Also supports parsing Persona (RoleGoalBackstory) from structured markdown.
 */
@Service
public class PromptProvider {

    private static final Logger log = LoggerFactory.getLogger(PromptProvider.class);
    private final Jinjava jinjava = new Jinjava();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    private static final String PROMPT_BASE_PATH = "prompts/";

    /**
     * Get rendered prompt.
     * @param path Relative path from src/main/resources/prompts/ (e.g., "agents/presales/search.jinja")
     * @param params Parameters for rendering
     * @return Rendered prompt string
     */
    public String getPrompt(String path, Map<String, Object> params) {
        String fullPath = PROMPT_BASE_PATH + path;
        try {
            String content = loadResource(fullPath);
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

    /**
     * Converts any objects (including Records) in the params map to nested Maps 
     * that Jinjava can easily navigate.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertRecordsToMaps(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return params;
        try {
            // Jackson은 Record를 JSON 노드로 변환할 때 필드 이름을 그대로 사용하므로, 
            // 이를 다시 Map으로 변환하면 Jinjava가 읽을 수 있는 형태가 됩니다.
            return objectMapper.convertValue(params, Map.class);
        } catch (Exception e) {
            log.warn("Failed to pre-process prompt params for Jinjava, using original params", e);
            return params;
        }
    }

    /**
     * Get static prompt without params.
     */
    public String getPrompt(String path) {
        return getPrompt(path, Collections.emptyMap());
    }

    /**
     * Get Persona (RoleGoalBackstory) parsed from a structured markdown file.
     * Expects sections like # ROLE, # GOAL, # BACKSTORY.
     */
    public RoleGoalBackstory getPersona(String path, Map<String, Object> params) {
        String content = getPrompt(path, params);
        String role = extractSection(content, "ROLE");
        String goal = extractSection(content, "GOAL");
        String backstory = extractSection(content, "BACKSTORY");
        
        if (role.isEmpty() && goal.isEmpty() && backstory.isEmpty()) {
            log.warn("Persona file at {} seems to have no valid sections (# ROLE, # GOAL, # BACKSTORY)", path);
        }
        
        return new RoleGoalBackstory(role, goal, backstory);
    }

    public RoleGoalBackstory getPersona(String path) {
        return getPersona(path, Collections.emptyMap());
    }

    private String extractSection(String content, String sectionName) {
        // Matches # SECTION_NAME or ## SECTION_NAME followed by content until next # or end of file
        Pattern pattern = Pattern.compile(
            "(?m)^#+\\s*" + sectionName + "\\s*$\\n?([\\s\\S]*?)(?=\\n#+|$)"
        );
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private String loadResource(String path) throws IOException {
        // Ensure path starts with / for absolute classpath lookup
        String resourcePath = path.startsWith("/") ? path : "/" + path;
        
        // Use the classloader that loaded PromptProvider to ensure we can see inside BOOT-INF/classes
        ClassPathResource resource = new ClassPathResource(resourcePath, getClass().getClassLoader());
        
        if (!resource.exists()) {
            log.error("Resource not found: {} (ClassLoader: {})", resourcePath, getClass().getClassLoader());
            throw new IOException("Resource not found: " + resourcePath);
        }
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
}
