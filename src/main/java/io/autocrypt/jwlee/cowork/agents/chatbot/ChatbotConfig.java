package io.autocrypt.jwlee.cowork.agents.chatbot;

import com.embabel.agent.prompt.persona.RoleGoalBackstory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatbotConfig.OrchestratorProps.class)
public class ChatbotConfig {

    @ConfigurationProperties("embabel.identities.chatbot.orchestrator")
    public record OrchestratorProps(String role, String goal, String backstory) {}

    @Bean
    public RoleGoalBackstory mainOrchestratorPersona(OrchestratorProps props) {
        return new RoleGoalBackstory(props.role(), props.goal(), props.backstory());
    }
}
