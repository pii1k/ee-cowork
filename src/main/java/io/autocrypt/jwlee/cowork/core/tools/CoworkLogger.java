package io.autocrypt.jwlee.cowork.core.tools;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Interface to decouple business logic from Terminal UI.
 * Uses Optional<Terminal> to gracefully fallback to SLF4J in non-terminal environments.
 * Standardizes log formatting across different levels.
 */
@Component
public class CoworkLogger {
    private static final Logger log = LoggerFactory.getLogger(CoworkLogger.class);
    private final Optional<Terminal> terminal;

    public CoworkLogger(Optional<Terminal> terminal) {
        this.terminal = terminal;
    }

    // --- Public API ---

    public void debug(String prefix, String message) {
        debug(prefix, message, null);
    }

    public void debug(String prefix, String message, Throwable throwable) {
        internalLog(LogLevel.DEBUG, prefix, message, throwable, AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE));
    }

    public void info(String prefix, String message) {
        info(prefix, message, null);
    }

    public void info(String prefix, String message, Throwable throwable) {
        internalLog(LogLevel.INFO, prefix, message, throwable, AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
    }

    public void warn(String prefix, String message) {
        warn(prefix, message, null);
    }

    public void warn(String prefix, String message, Throwable throwable) {
        internalLog(LogLevel.WARN, prefix, message, throwable, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
    }

    public void error(String prefix, String message) {
        error(prefix, message, null);
    }

    public void error(String prefix, String message, Throwable throwable) {
        internalLog(LogLevel.ERROR, prefix, message, throwable, AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
    }

    // --- Internal Logic ---

    private void internalLog(LogLevel level, String prefix, String message, Throwable throwable, AttributedStyle style) {
        // 1. SLF4J Logging
        logToSlf4j(level, prefix, message, throwable);

        // 2. Terminal Logging
        terminal.ifPresent(t -> {
            String formattedMessage = formatTerminalMessage(level, prefix, message, throwable);
            t.writer().println(new AttributedString(formattedMessage, style).toAnsi());
            t.writer().flush();
        });
    }

    private String formatTerminalMessage(LogLevel level, String prefix, String message, Throwable throwable) {
        // INFO 레벨은 가독성을 위해 태그를 생략하고 'prefix: message' 형식 유지
        String levelTag = (level == LogLevel.INFO) ? "" : "[" + level.name() + "] ";
        
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(": ").append(levelTag).append(message);

        if (throwable != null) {
            String exMsg = throwable.getMessage();
            String exName = throwable.getClass().getSimpleName();
            sb.append(" (").append(exMsg != null ? exMsg : exName).append(")");
        }
        
        return sb.toString();
    }

    private void logToSlf4j(LogLevel level, String prefix, String message, Throwable throwable) {
        String slf4jMsg = "[{}] {}";
        switch (level) {
            case DEBUG -> log.debug(slf4jMsg, prefix, message, throwable);
            case INFO -> log.info(slf4jMsg, prefix, message, throwable);
            case WARN -> log.warn(slf4jMsg, prefix, message, throwable);
            case ERROR -> log.error(slf4jMsg, prefix, message, throwable);
        }
    }

    private enum LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
}
