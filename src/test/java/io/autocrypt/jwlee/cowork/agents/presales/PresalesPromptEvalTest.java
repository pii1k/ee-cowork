package io.autocrypt.jwlee.cowork.agents.presales;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import com.embabel.agent.api.common.Ai;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.Jinjava;

import io.autocrypt.jwlee.cowork.core.eval.ModelGrader;
import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;

import io.autocrypt.jwlee.cowork.BaseIntegrationTest;

public class PresalesPromptEvalTest extends BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PresalesPromptEvalTest.class);

    @Autowired
    private PromptProvider promptProvider;

    @Autowired
    private ModelGrader modelGrader;

    @Autowired
    private Ai ai;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Jinjava jinjava = new Jinjava();

    public record TestCase(String inquiry) {}
    public record EvalResultEntry(String inquiry, ModelGrader.EvalResult grade) {}

    @Test
    public void evaluateCrsGenerationPrompt() throws IOException {
        // 1. Load Dataset
        List<TestCase> dataset = objectMapper.readValue(
            new ClassPathResource("evals/presales/dataset.json").getInputStream(),
            new TypeReference<List<TestCase>>() {}
        );

        List<EvalResultEntry> results = new ArrayList<>();

        log.info("Starting Prompt Evaluation for PresalesAgent CRS Generation...");

        for (TestCase tc : dataset) {
            // 2. Generate Prompt using PromptProvider
            String techContext = "Standard technical specifications related to " + tc.inquiry();
            String prompt = promptProvider.getPrompt("agents/presales/refine-requirements-final.jinja", Map.of(
                "sourceContent", tc.inquiry(),
                "techContext", techContext
            ));

            // 3. Get LLM Response
            String output = ai.withLlmByRole("normal").generateText(prompt);

            // 4. Grade the result
            ModelGrader.EvalResult grade = modelGrader.grade(tc.inquiry(), output);
            results.add(new EvalResultEntry(tc.inquiry(), grade));

            log.info("Finished Test Case: {} (Score: {})", tc.inquiry(), grade.score());
        }

        // 5. Generate HTML Report
        double averageScore = results.stream().mapToDouble(r -> r.grade().score()).average().orElse(0.0);
        generateHtmlReport(results, averageScore);

        log.info("==================================================");
        log.info("FINAL EVALUATION REPORT GENERATED");
        log.info("Average Score: {} / 10.0", String.format("%.2f", averageScore));
        log.info("==================================================");
    }

    private void generateHtmlReport(List<EvalResultEntry> results, double averageScore) throws IOException {
        // 템플릿 로드
        String template = Files.readString(
            Paths.get("src/test/resources/prompts/eval/report-template.jinja"), 
            StandardCharsets.UTF_8
        );

        // Record를 Jinjava가 읽을 수 있는 Map 리스트로 변환
        List<Map<String, Object>> mapResults = results.stream()
            .map(r -> Map.<String, Object>of(
                "inquiry", r.inquiry(),
                "grade", Map.of(
                    "score", r.grade().score(),
                    "reasoning", r.grade().reasoning(),
                    "strengths", r.grade().strengths(),
                    "weaknesses", r.grade().weaknesses()
                )
            ))
            .toList();

        // 데이터 바인딩
        Map<String, Object> context = Map.of(
            "results", mapResults,
            "averageScore", averageScore
        );

        // 렌더링
        String html = jinjava.render(template, context);

        // 파일 저장
        Path outputPath = Paths.get("output/eval/presales-report.html");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, html, StandardCharsets.UTF_8);

        log.info("HTML Report saved to: {}", outputPath.toAbsolutePath());
    }
}
