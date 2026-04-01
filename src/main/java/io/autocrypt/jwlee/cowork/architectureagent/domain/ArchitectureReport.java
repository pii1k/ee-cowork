package io.autocrypt.jwlee.cowork.architectureagent.domain;

import java.util.List;

/**
 * Structured report of the codebase architecture.
 */
public record ArchitectureReport(
    String summary,
    String technicalStack,
    List<ModuleInfo> modules,
    List<String> entryPoints,
    String architecturePattern,
    List<String> keyConventions,
    String recommendations
) {
    public record ModuleInfo(
        String name,
        String path,
        String responsibility,
        List<String> dependencies
    ) {}
}
