package io.autocrypt.jwlee.cowork.agents.chatbot;

import com.embabel.chat.ChatSession;
import com.embabel.chat.Chatbot;
import com.embabel.chat.UserMessage;
import com.embabel.agent.api.channel.MessageOutputChannelEvent;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.jline.terminal.Terminal;

@ShellComponent
public class ChatbotCommand {

    private final Chatbot chatbot;
    private final Terminal terminal;
    private ChatSession currentSession;

    public ChatbotCommand(Chatbot chatbot, Terminal terminal) {
        this.chatbot = chatbot;
        this.terminal = terminal;
    }

    @ShellMethod(value = "Ask the main orchestrator chatbot a question or give a task.", key = "ask")
    public void ask(@ShellOption(help = "The message to send to the chatbot") String message) {
        if (currentSession == null) {
            // Create a session that prints AI responses to the terminal
            currentSession = chatbot.createSession(null, event -> {
                if (event instanceof MessageOutputChannelEvent me) {
                    terminal.writer().println("\n[Bot]: " + me.getMessage().getContent());
                    terminal.writer().flush();
                }
            }, null, "main-orchestrator-session");
        }

        currentSession.onUserMessage(new UserMessage(message));
    }

    @ShellMethod(value = "Reset the chatbot session memory.", key = "ask-reset")
    public String reset() {
        this.currentSession = null;
        return "Chat session reset.";
    }
}
