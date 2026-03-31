package io.autocrypt.jwlee.cowork.web.agent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface Agent {
    String getId();
    String getName();
    String getDescription();
    String getRole();

    /**
     * Executes the agent logic asynchronously.
     * @param params Key-value pairs from the UI form.
     * @return Future containing the markdown report content.
     */
    CompletableFuture<String> execute(Map<String, String> params);
}
