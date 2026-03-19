package io.autocrypt.jwlee.cowork.agents.chatbot;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.prompt.persona.RoleGoalBackstory;
import com.embabel.chat.Conversation;
import com.embabel.chat.Message;
import com.embabel.chat.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Agent(description = "Main Orchestrator Agent for general chat and task coordination")
@Component
public class ChatbotAgent {

    private final RoleGoalBackstory mainOrchestratorPersona;

    public ChatbotAgent(RoleGoalBackstory mainOrchestratorPersona) {
        this.mainOrchestratorPersona = mainOrchestratorPersona;
    }

    /**
     * Responds to user messages using the cheapest model and maintaining memory of last 10 turns.
     */
    @Action(canRerun = true, trigger = UserMessage.class)
    public void chat(Conversation conversation, ActionContext context, Ai ai) {
        List<Message> messages = conversation.getMessages();
        
        // Keep only the last 10 messages for context efficiency
        List<Message> contextMessages = messages.size() > 10 
                ? messages.subList(messages.size() - 10, messages.size()) 
                : messages;

        // Use 'cheapest' model role and 'mainOrchestratorPersona' for identity
        // Patterns followed from WeeklyReportAgent
        var response = ai.withLlmByRole("cheapest")
                .withPromptContributor(mainOrchestratorPersona)
                .respond(contextMessages);

        context.sendMessage(conversation.addMessage(response));
    }
}
