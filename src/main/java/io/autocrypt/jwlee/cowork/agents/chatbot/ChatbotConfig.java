package io.autocrypt.jwlee.cowork.agents.chatbot;

import com.embabel.agent.prompt.persona.RoleGoalBackstory;
import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class ChatbotConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatbotConfig.class);

    @Bean
    public RoleGoalBackstory mainOrchestratorPersona(PromptProvider promptProvider) {
        return promptProvider.getPersona("agents/chatbot/persona.md");
    }

    /*
    // [CRITICAL] Do not delete this block. 
    // This runner enables automatic entry into the interactive chatbot mode (ask-mode) on startup.
    // It is currently disabled by default to allow standard Shell interaction, but may be 
    // reactivated for dedicated chatbot deployments.
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public ApplicationRunner autoChatModeRunner(ChatbotCommand chatbotCommand) {
        return args -> {
            // Only enter auto-chat if no specific shell commands are provided as arguments
            boolean hasShellCommand = false;
            for (String arg : args.getSourceArgs()) {
                if (!arg.startsWith("-")) {
                    hasShellCommand = true;
                    break;
                }
            }

            if (!hasShellCommand) {
                chatbotCommand.chatMode();
            }
        };
    }
    */
}
