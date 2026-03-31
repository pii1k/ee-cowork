package io.autocrypt.jwlee.cowork.web.core.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CoworkLogger {
    private static final Logger log = LoggerFactory.getLogger(CoworkLogger.class);

    public void info(String prefix, String message) {
        log.info("[{}] {}", prefix, message);
    }

    public void error(String prefix, String message) {
        log.error("[{}] {}", prefix, message);
    }
}
