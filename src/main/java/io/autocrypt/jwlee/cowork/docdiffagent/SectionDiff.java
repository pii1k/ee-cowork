package io.autocrypt.jwlee.cowork.docdiffagent;

public record SectionDiff(
        String title,
        String changeType, // ADDED, REMOVED, MODIFIED
        String technicalSummary,
        String impact,
        boolean isBreaking
) {}
