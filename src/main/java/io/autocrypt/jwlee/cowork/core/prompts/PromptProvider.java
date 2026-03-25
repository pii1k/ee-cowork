package io.autocrypt.jwlee.cowork.core.prompts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import com.hubspot.jinjava.Jinjava;

/**
 * PromptProvider responsible for loading and rendering prompts from resources.
 * Supports .jinja for dynamic templates and .md/.txt for static prompts.
 */
@Service
public class PromptProvider {

    private static final Logger log = LoggerFactory.getLogger(PromptProvider.class);
    private final Jinjava jinjava = new Jinjava();
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
                return jinjava.render(content, params != null ? params : Collections.emptyMap());
            }
            return content;
        } catch (IOException e) {
            log.error("Failed to load prompt from path: {}", fullPath, e);
            throw new RuntimeException("Prompt not found: " + fullPath, e);
        }
    }

    /**
     * Get static prompt without params.
     */
    public String getPrompt(String path) {
        return getPrompt(path, Collections.emptyMap());
    }

    private String loadResource(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
}
