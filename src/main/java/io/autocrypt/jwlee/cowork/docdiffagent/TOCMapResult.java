package io.autocrypt.jwlee.cowork.docdiffagent;

import java.util.List;

public record TOCMapResult(
        String sourceVersion,
        String targetVersion,
        List<TOCEntry> added,
        List<TOCEntry> removed,
        List<MappedSection> modified
) {}
