package io.autocrypt.jwlee.cowork.chatbotagent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.tool.Subagent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.prompt.persona.RoleGoalBackstory;
import com.embabel.agent.rag.tools.TryHyDE;
import io.autocrypt.jwlee.cowork.architectureagent.ArchitectureAgent;
import io.autocrypt.jwlee.cowork.architectureagent.domain.ArchitectureRequest;
import io.autocrypt.jwlee.cowork.core.workaround.JsonSafeToolishRag;
import com.embabel.chat.Conversation;
import com.embabel.chat.Message;
import com.embabel.chat.UserMessage;
import io.autocrypt.jwlee.cowork.core.tools.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@EmbabelComponent
@Component
public class ChatbotAgent {

    private final RoleGoalBackstory mainOrchestratorPersona;
    private final FileReadTool readTool;
    private final FileWriteTool writeTool;
    private final FileEditTool editTool;
    private final GlobTool globTool;
    private final GrepTool grepTool;
    private final BashTool bashTool;
    private final LocalRagTools localRagTools;

    public ChatbotAgent(AgentPlatform agentPlatform,
                        RoleGoalBackstory mainOrchestratorPersona, 
                        FileReadTool readTool,
                        FileWriteTool writeTool,
                        FileEditTool editTool,
                        GlobTool globTool,
                        GrepTool grepTool,
                        BashTool bashTool,
                        LocalRagTools localRagTools) {
        this.mainOrchestratorPersona = mainOrchestratorPersona;
        this.readTool = readTool;
        this.writeTool = writeTool;
        this.editTool = editTool;
        this.globTool = globTool;
        this.grepTool = grepTool;
        this.bashTool = bashTool;
        this.localRagTools = localRagTools;
    }

    /**
     * Wrapper tool that fixes the RAG name to 'chatbot' and returns JSON.
     */
    public class ChatbotRagWrapper {
        @LlmTool(description = "Ingest a URL or file into the chatbot's in-memory knowledge base. ONLY use this when the user explicitly says 'Add RAG' or 'RAG 추가해'.")
        public String ingest(String location) throws IOException {
            String result = localRagTools.ingestUrlToMemory(location, "chatbot");
            return "{\"result\": \"" + result.replace("\"", "'") + "\"}";
        }

        @LlmTool(description = "Ingest a directory of files into the chatbot's in-memory knowledge base. ONLY use this when the user explicitly says 'Add RAG' or 'RAG 추가해'.")
        public String ingestDirectory(String directoryPath) throws IOException {
            String result = localRagTools.ingestDirectoryToMemory(directoryPath, "chatbot");
            return "{\"result\": \"" + result.replace("\"", "'") + "\"}";
        }
    }

    @Action(canRerun = true, trigger = UserMessage.class)
    public void chat(Conversation conversation, ActionContext context, Ai ai) throws IOException {
        List<Message> messages = conversation.getMessages();
        List<Message> contextMessages = messages.size() > 10 
                ? messages.subList(messages.size() - 10, messages.size()) 
                : messages;

        var searchOps = localRagTools.getOrOpenMemoryInstance("chatbot");
        var toolishRag = new JsonSafeToolishRag("knowledge", "General knowledge base containing uploaded documents and URLs", searchOps)
                .withHint(TryHyDE.usingConversationContext());
        
        var response = ai.withLlmByRole("simple")
                .withPromptContributor(mainOrchestratorPersona)
                .withToolObject(readTool)
                .withToolObject(writeTool)
                .withToolObject(editTool)
                .withToolObject(globTool)
                .withToolObject(grepTool)
                .withToolObject(bashTool)
                .withToolObject(new ChatbotRagWrapper())
                .withTool(Subagent.ofClass(ArchitectureAgent.class).consuming(ArchitectureRequest.class))
                .withReference(toolishRag)
                .respond(contextMessages);

        context.sendMessage(conversation.addMessage(response));
    }
}
