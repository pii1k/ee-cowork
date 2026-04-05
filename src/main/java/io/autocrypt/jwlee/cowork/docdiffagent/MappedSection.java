package io.autocrypt.jwlee.cowork.docdiffagent;

public record MappedSection(TOCEntry source, TOCEntry target, String similarityReason) {}
