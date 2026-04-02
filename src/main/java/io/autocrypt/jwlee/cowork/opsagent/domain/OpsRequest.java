package io.autocrypt.jwlee.cowork.opsagent.domain;

/**
 * Request DTO for OpsAgent infrastructure and operations analysis.
 */
public record OpsRequest(String path, String context) {}
