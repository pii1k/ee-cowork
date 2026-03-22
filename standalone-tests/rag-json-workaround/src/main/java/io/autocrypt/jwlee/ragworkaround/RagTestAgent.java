package io.autocrypt.jwlee.ragworkaround;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.domain.io.UserInput;
import org.springframework.stereotype.Component;

@Agent(description = "Agent for testing JSON workaround")
@Component
public class RagTestAgent {

    private final JsonSafeToolishRag toolishRag;

    public RagTestAgent(JsonSafeToolishRag toolishRag) {
        this.toolishRag = toolishRag;
    }

    @AchievesGoal(description = "Provides the user with a string answer")
    @Action
    public String search(UserInput input, ActionContext ctx) {
        return ctx.ai().withDefaultLlm()
                .withReference(toolishRag)
                .generateText(String.format("Answer the user's question using the 'docs' tool. Question: %s", input.getContent()));
    }
}
