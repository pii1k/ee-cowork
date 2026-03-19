package io.autocrypt.jwlee.cowork.agents.chatbot;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.prompt.persona.RoleGoalBackstory;
import com.embabel.chat.Conversation;
import com.embabel.chat.Message;
import com.embabel.chat.UserMessage;
import io.autocrypt.jwlee.cowork.core.tools.CoreFileTools;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Agent(description = "Main Orchestrator Agent for general chat and task coordination")
@Component
public class ChatbotAgent {

    private final RoleGoalBackstory mainOrchestratorPersona;
    private final CoreFileTools coreFileTools;

    public ChatbotAgent(RoleGoalBackstory mainOrchestratorPersona, CoreFileTools coreFileTools) {
        this.mainOrchestratorPersona = mainOrchestratorPersona;
        this.coreFileTools = coreFileTools;
    }

    /**
     * Tool: writeFile
     * Returns a structured FileResult to ensure reliable communication with the LLM.
     */
    @LlmTool(description = "Writes content to a NEW file. Fails if file exists. Returns FileResult.")
    public CoreFileTools.FileResult writeFile(String path, String content) throws IOException {
        return coreFileTools.writeFile(path, content);
    }

    /**
     * Responds to user messages while exposing 'this' as a tool container.
     */
    @Action(canRerun = true, trigger = UserMessage.class)
    public void chat(Conversation conversation, ActionContext context, Ai ai) {
        List<Message> messages = conversation.getMessages();
        List<Message> contextMessages = messages.size() > 10 
                ? messages.subList(messages.size() - 10, messages.size()) 
                : messages;

        var response = ai.withLlmByRole("cheapest")
                .withPromptContributor(mainOrchestratorPersona)
                .withToolObject(this) 
                .respond(contextMessages);

        context.sendMessage(conversation.addMessage(response));
    }
}
