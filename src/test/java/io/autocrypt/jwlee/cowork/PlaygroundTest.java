package io.autocrypt.jwlee.cowork;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;

/**
 * 다양한 LLM 실험 및 간이 테스트를 위한 플레이그라운드입니다.
 */
public class PlaygroundTest extends BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PlaygroundTest.class);

    @Autowired
    private Ai ai;

    @Test
    public void reasoningDemo() {
        String modelName = "gemini-3-flash-preview";
        String prompt = "9.11과 9.9 중 어느 숫자가 더 큰가요? 이유를 설명해주세요.";

        log.info("==================================================");
        log.info("Reasoning Demo: Comparing Thinking vs WithoutThinking");
        log.info("Model: {}", modelName);
        log.info("Prompt: {}", prompt);
        log.info("==================================================");

        // 1. Without Thinking
        log.info("[Executing WITHOUT Thinking...]");
        String fastResult = ai.withLlm(LlmOptions.withModel(modelName).withoutThinking())
                .generateText(prompt);
        log.info("Result (Without Thinking):\n{}", fastResult);

        log.info("--------------------------------------------------");

        // 2. With Thinking
        log.info("[Executing WITH Thinking (Reasoning)...]");
        String reasoningResult = ai.withLlm(LlmOptions.withModel(modelName))
                .generateText(prompt);
        log.info("Result (With Thinking):\n{}", reasoningResult);

        log.info("==================================================");
    }

    @Test
    public void scalingLawDemo() {
        String prompt = "노인과 바다의 ISBN을 알려줘";

        log.info("==================================================");
        log.info("Scaling Law Demo: Comparing 'simple' vs 'normal' roles");
        log.info("Prompt: {}", prompt);
        log.info("==================================================");

        String resultLite = ai.withLlmByRole("simple").generateText(prompt);
        log.info("Result (simple):\n{}", resultLite);

        log.info("--------------------------------------------------");

        String resultNormal = ai.withLlmByRole("normal").generateText(prompt);
        log.info("Result (normal):\n{}", resultNormal);

        log.info("==================================================");
    }
}
