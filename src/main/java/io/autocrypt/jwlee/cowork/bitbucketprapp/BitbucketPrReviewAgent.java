package io.autocrypt.jwlee.cowork.bitbucketprapp;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;
import io.autocrypt.jwlee.cowork.core.tools.ConfluenceService;
import io.autocrypt.jwlee.cowork.core.tools.CoreWorkspaceProvider;
import io.autocrypt.jwlee.cowork.core.tools.CoworkLogger;
import io.autocrypt.jwlee.cowork.core.tools.LocalRagTools;
import io.autocrypt.jwlee.cowork.core.workaround.JsonSafeToolishRag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Agent(description = "Bitbucket PR의 변경사항을 분석하여 논리, 제품 스펙, 스타일 가이드, 테스트 충실도를 검사하는 에이전트")
@Component
public class BitbucketPrReviewAgent {

    private final BitbucketService bitbucketService;
    private final LocalRagTools localRagTools;
    private final CoreWorkspaceProvider workspaceProvider;
    private final ConfluenceService confluenceService;
    private final CoworkLogger logger;

    public BitbucketPrReviewAgent(BitbucketService bitbucketService,
                                  LocalRagTools localRagTools,
                                  CoreWorkspaceProvider workspaceProvider,
                                  ConfluenceService confluenceService,
                                  CoworkLogger logger) {
        this.bitbucketService = bitbucketService;
        this.localRagTools = localRagTools;
        this.workspaceProvider = workspaceProvider;
        this.confluenceService = confluenceService;
        this.logger = logger;
    }

    // DTOs
    public record PrReviewRequest(
            @NotBlank String repositorySlug,
            @Min(1) Long pullRequestId,
            String manualsDir,
            String standardsDir,
            String styleGuideUrl
    ) {}

    public record CodeComment(
            String fileName,
            Integer lineNumber,
            String content,
            String type, // "GLOBAL" | "LINE"
            String severity, // "MUST_FIX" | "SHOULD_FIX" | "SUGGESTION"
            String criteriaId
    ) {}

    public record AnalysisResult(
            List<CodeComment> comments,
            int score,
            boolean isTruncated
    ) {}

    public record FinalReviewReport(
            int overallScore,
            String summary,
            List<CodeComment> globalComments,
            List<CodeComment> lineComments,
            int totalIssuesFound,
            List<String> truncatedFiles
    ) {}

    public record AllAnalysisResults(
            ContextReadyState context,
            List<AnalysisResult> results
    ) {}

    public record DiffSegment(
            String fileName,
            String diffContent,
            boolean isTruncated,
            int totalLines
    ) {}

    // States
    @State
    public record InitialState(PrReviewRequest request) {}

    @State
    public record ContextReadyState(
            PrReviewRequest request,
            List<DiffSegment> diffSegments,
            String manualsRagKey,
            String standardsRagKey,
            String styleGuideContent
    ) {}

    @Action
    public ContextReadyState prepareReviewContext(InitialState state) throws IOException {
        var req = state.request();
        logger.info("BitbucketPrReview", "Preparing review context for PR " + req.pullRequestId());

        // 1. RAG 인스턴스 초기화 및 인덱싱
        String manualsRagKey = "manuals-" + workspaceProvider.toSlug(req.manualsDir());
        String standardsRagKey = "standards-" + workspaceProvider.toSlug(req.standardsDir());

        if (req.manualsDir() != null && java.nio.file.Files.isDirectory(java.nio.file.Path.of(req.manualsDir()))) {
            localRagTools.ingestDirectory(req.manualsDir(), manualsRagKey);
        }
        if (req.standardsDir() != null && java.nio.file.Files.isDirectory(java.nio.file.Path.of(req.standardsDir()))) {
            localRagTools.ingestDirectory(req.standardsDir(), standardsRagKey);
        }

        // 2. 스타일 가이드 내용 가져오기 (Core 도구 활용)
        String styleGuideContent = "스타일 가이드 내용을 가져오지 못했습니다.";
        if (req.styleGuideUrl() != null) {
            String pageId = extractPageId(req.styleGuideUrl());
            if (pageId != null) {
                logger.info("BitbucketPrReview", "Fetching Confluence style guide content for pageId: " + pageId);
                String content = confluenceService.getPageStorage(pageId);
                if (content != null && !content.isBlank()) {
                    styleGuideContent = content;
                }
            }
        }

        // 3. Bitbucket PR Diff 가져오기 및 세그먼트 분리
        String workspace = "autocrypt"; 
        String repo = req.repositorySlug();
        if (req.repositorySlug().contains("/")) {
            String[] parts = req.repositorySlug().split("/");
            workspace = parts[0];
            repo = parts[1];
        }

        PullRequestData prData = bitbucketService.fetchPullRequest(workspace, repo, String.valueOf(req.pullRequestId()));
        List<DiffSegment> segments = splitDiff(prData.diff());

        return new ContextReadyState(req, segments, manualsRagKey, standardsRagKey, styleGuideContent);
    }

    private String extractPageId(String url) {
        if (url == null) return null;
        // Case 1: https://.../pages/878641171/C
        Pattern pattern = Pattern.compile("/pages/(\\d+)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // Case 2: x/EwBfN 형태의 단축 URL은 현재 resolve 로직이 없으므로, 
        // 알려진 Golden Rule ID로 매핑하거나 추가 로직 필요.
        if (url.contains("EwBfN")) return "878641171";
        
        return null;
    }

    @Action
    public AllAnalysisResults analyzeAllSegments(ContextReadyState context, Ai ai) throws IOException {
        logger.info("BitbucketPrReview", "Starting analysis of " + context.diffSegments().size() + " segments");
        List<AnalysisResult> results = new ArrayList<>();

        // RAG 도구 준비
        var manualsRag = new JsonSafeToolishRag("manuals", "제품 매뉴얼 지식", localRagTools.getOrOpenInstance(context.manualsRagKey()));
        var standardsRag = new JsonSafeToolishRag("standards", "표준 문서 지식", localRagTools.getOrOpenInstance(context.standardsRagKey()));

        for (DiffSegment segment : context.diffSegments()) {
            logger.info("BitbucketPrReview", "Analyzing segment: " + segment.fileName());
            AnalysisResult result = ai.withLlm(LlmOptions.withLlmForRole("normal")
                            .withoutThinking()
                            .withTemperature(0.1)
                            .withMaxTokens(65536))
                    .withReference(standardsRag) // 표준이 우선
                    .withReference(manualsRag)
                    .rendering("agents/bitbucketprapp/analyze-code")
                    .createObject(AnalysisResult.class, Map.of(
                            "fileName", segment.fileName(),
                            "diffContent", segment.diffContent(),
                            "isTruncated", segment.isTruncated(),
                            "style_guide_content", context.styleGuideContent()
                    ));
            results.add(result);
        }
        return new AllAnalysisResults(context, results);
    }

    @AchievesGoal(description = "모든 분석 결과를 수합하여 최종 리포트를 생성하고 코멘트를 게시함")
    @Action
    public FinalReviewReport synthesizeFinalReport(AllAnalysisResults allResults, ActionContext ctx) {
        var context = allResults.context();
        logger.info("BitbucketPrReview", "Synthesizing final report for PR " + context.request().pullRequestId());

        List<CodeComment> allComments = allResults.results().stream()
                .filter(r -> r.comments() != null)
                .flatMap(r -> r.comments().stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 중복 제거 및 정리 로직
        List<CodeComment> globalComments = allComments.stream()
                .filter(c -> c.lineNumber() == null || "GLOBAL".equals(c.type()))
                .collect(Collectors.toList());

        List<CodeComment> lineComments = allComments.stream()
                .filter(c -> c.lineNumber() != null && !"GLOBAL".equals(c.type()))
                .sorted(Comparator.comparing(CodeComment::fileName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(CodeComment::lineNumber, Comparator.nullsLast(Integer::compareTo)))
                .collect(Collectors.toList());

        int totalScore = allResults.results().isEmpty() ? 100 : 
                (int) allResults.results().stream().mapToInt(AnalysisResult::score).average().orElse(100);

        List<String> truncatedFiles = context.diffSegments().stream()
                .filter(DiffSegment::isTruncated)
                .map(DiffSegment::fileName)
                .collect(Collectors.toList());

        StringBuilder summaryBuilder = new StringBuilder();
        summaryBuilder.append("## AI Code Review Summary\n");
        summaryBuilder.append(String.format("**Overall Score: %d/100**\n\n", totalScore));
        summaryBuilder.append("### Key Findings\n");
        for (CodeComment gc : globalComments) {
            summaryBuilder.append(String.format("- [%s] %s\n", gc.severity(), gc.content()));
        }

        FinalReviewReport report = new FinalReviewReport(
                totalScore,
                summaryBuilder.toString(),
                globalComments,
                lineComments,
                allComments.size(),
                truncatedFiles
        );

        String workspace = "autocrypt"; 
        String repo = context.request().repositorySlug();
        if (context.request().repositorySlug().contains("/")) {
            String[] parts = context.request().repositorySlug().split("/");
            workspace = parts[0];
            repo = parts[1];
        }

        bitbucketService.postGlobalComment(workspace, repo, String.valueOf(context.request().pullRequestId()), report.summary());

        for (CodeComment lc : lineComments) {
            if (lc.fileName() == null) continue;
            String content = String.format("[%s] %s (Criteria: %s)", lc.severity(), lc.content(), lc.criteriaId());
            bitbucketService.postLineComment(workspace, repo, String.valueOf(context.request().pullRequestId()), lc.fileName(), lc.lineNumber() != null ? lc.lineNumber() : 1, content);
        }

        for (DiffSegment ds : context.diffSegments()) {
            if (ds.isTruncated()) {
                bitbucketService.postLineComment(workspace, repo, String.valueOf(context.request().pullRequestId()), ds.fileName(), 1, 
                        "이 파일은 길이가 길어(500줄 초과) 상단부만 분석되었습니다.");
            }
        }

        return report;
    }

    private List<DiffSegment> splitDiff(String rawDiff) {
        List<DiffSegment> segments = new ArrayList<>();
        if (rawDiff == null || rawDiff.isBlank()) return segments;

        String[] parts = rawDiff.split("diff --git ");
        for (String part : parts) {
            if (part.isBlank()) continue;

            String[] lines = part.split("\n");
            String firstLine = lines[0];
            String fileName = "unknown";
            int bIdx = firstLine.indexOf(" b/");
            if (bIdx != -1) {
                fileName = firstLine.substring(bIdx + 3).trim();
            }

            int totalLines = lines.length;
            boolean isTruncated = false;
            String diffContent = "diff --git " + part;

            if (totalLines > 500) {
                isTruncated = true;
                diffContent = diffContent.lines().limit(500).collect(Collectors.joining("\n")) + "\n... (Truncated) ...";
            }

            segments.add(new DiffSegment(fileName, diffContent, isTruncated, totalLines));
        }
        return segments;
    }
}
