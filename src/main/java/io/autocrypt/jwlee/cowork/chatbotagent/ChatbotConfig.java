package io.autocrypt.jwlee.cowork.chatbotagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.embabel.agent.prompt.persona.RoleGoalBackstory;

import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;

@Configuration
public class ChatbotConfig {

    @Bean
    public RoleGoalBackstory mainOrchestratorPersona(PromptProvider promptProvider) {
        return promptProvider.getPersona("agents/chatbot/persona.md");
    }
}
