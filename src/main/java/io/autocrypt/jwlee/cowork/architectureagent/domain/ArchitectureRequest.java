package io.autocrypt.jwlee.cowork.architectureagent.domain;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Request for architecture analysis.
 */
public record ArchitectureRequest(
    @NotBlank(message = "Path to analyze is required")
    String path,
    
    @NotBlank(message = "Introduction or context of the codebase is required")
    String context
) {}
