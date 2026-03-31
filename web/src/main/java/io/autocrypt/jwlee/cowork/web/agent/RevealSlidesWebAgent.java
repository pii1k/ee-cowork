package io.autocrypt.jwlee.cowork.web.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.common.ai.model.LlmOptions;
import io.autocrypt.jwlee.cowork.web.core.prompts.PromptProvider;
import io.autocrypt.jwlee.cowork.web.core.tools.CoworkLogger;
import io.autocrypt.jwlee.cowork.web.core.tools.LocalRagTools;
import io.autocrypt.jwlee.cowork.web.core.workaround.JsonSafeToolishRag;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class RevealSlidesWebAgent implements io.autocrypt.jwlee.cowork.web.agent.Agent {

    private final LocalRagTools localRagTools;
    private final PromptProvider promptProvider;
    private final CoworkLogger logger;
    private final AgentPlatform agentPlatform;

    public RevealSlidesWebAgent(LocalRagTools localRagTools, PromptProvider promptProvider, CoworkLogger logger, AgentPlatform agentPlatform) {
        this.localRagTools = localRagTools;
        this.promptProvider = promptProvider;
        this.logger = logger;
        this.agentPlatform = agentPlatform;
    }

    @Override
    public String getId() { return "reveal-slides"; }

    @Override
    public String getName() { return "Reveal.js 슬라이드 생성기"; }

    @Override
    public String getDescription() { return "문서와 지침을 바탕으로 Reveal.js 호환 온라인 발표자료를 생성합니다."; }

    @Override
    public String getRole() { return "Communicator"; }

    public record SlideRequest(String instructions, String ragName) {}
    public record SlideOutput(String markdown) {}

    @Agent(description = "Internal Logic for Reveal Slides")
    @Component
    public static class RevealLogicAgent {
        private final LocalRagTools localRagTools;
        private final PromptProvider promptProvider;

        public RevealLogicAgent(LocalRagTools localRagTools, PromptProvider promptProvider) {
            this.localRagTools = localRagTools;
            this.promptProvider = promptProvider;
        }

        @AchievesGoal(description = "Reveal.js Markdown Generated")
        @Action
        public SlideOutput generate(SlideRequest req, ActionContext ctx) throws Exception {
            var ragSearch = localRagTools.getOrOpenMemoryInstance(req.ragName());
            var rag = new JsonSafeToolishRag("knowledge_base", "Reference materials for slides", ragSearch);

            var ai = ctx.ai().withLlmByRole("normal").withReference(rag);

            // 1. Analyze structure (Simplified from original AdvancedSlidesAgent)
            String outlinePrompt = "자료를 분석하여 발표자료의 목차와 핵심 내용을 구성해줘.\n지침: " + req.instructions();
            String outline = ai.generateText(outlinePrompt);

            // 2. Generate Final Markdown (Explicitly for Reveal.js)
            String markdownPrompt = String.format("""
                위의 목차를 바탕으로 Reveal.js 호환 마크다운 발표자료를 작성해줘.
                
                # 지침:
                %s
                
                # 목차:
                %s
                
                # 작성 규칙 (Reveal.js / Reveal.md 규격):
                - 절대로 결과를 ```markdown ... ``` 처럼 백틱으로 감싸지 마라. 오직 순수 마크다운 내용만 출력하라.
                - 슬라이드 구분자(Horizontal Slide)는 '---' (엔터 포함)를 사용한다.
                - 수직 슬라이드 구분자(Vertical Slide)는 '--' 를 사용한다.
                - 가급적 시각적인 구조(Bullet points, Quotes, Tables)를 활용하여 풍부하게 작성하라.
                - 별도의 배경색이나 테마 옵션 주석(<!-- .slide: ... -->)은 사용하지 마라.
                - 반드시 마크다운 내용만 출력하라.
                """, req.instructions(), outline);

            String markdown = ai.withLlm(LlmOptions.withLlmForRole("performant").withMaxTokens(65536))
                                .generateText(markdownPrompt);

            return new SlideOutput(markdown);
        }
    }

    @Override
    @Async
    public CompletableFuture<String> execute(Map<String, String> params) {
        String instructions = params.getOrDefault("content", "발표자료를 만들어줘.");
        String filePaths = params.get("tech_ref_file"); // Use generic file param
        String taskId = params.get("taskId");

        return CompletableFuture.supplyAsync(() -> {
            String ragName = "slides-mem-" + taskId;
            try {
                if (filePaths != null && !filePaths.isEmpty()) {
                    for (String path : filePaths.split(",")) {
                        localRagTools.ingestUrlToMemory(path.trim(), ragName);
                    }
                }

                var invocation = AgentInvocation.create(agentPlatform, SlideOutput.class);
                var process = invocation.runAsync(new SlideRequest(instructions, ragName)).get();
                
                while (!process.getFinished()) { Thread.sleep(500); }
                
                return process.resultOfType(SlideOutput.class).markdown();
            } catch (Exception e) {
                logger.error("RevealSlides", "Failed: " + e.getMessage());
                throw new RuntimeException(e);
            } finally {
                localRagTools.closeMemoryInstance(ragName);
                deleteTempFiles(filePaths);
            }
        });
    }

    private void deleteTempFiles(String filePaths) {
        if (filePaths != null && !filePaths.isEmpty()) {
            for (String path : filePaths.split(",")) {
                try { java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path.trim())); } catch (Exception e) { }
            }
        }
    }
}
