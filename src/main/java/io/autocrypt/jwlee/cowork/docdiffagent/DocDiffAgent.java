package io.autocrypt.jwlee.cowork.docdiffagent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;
import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;
import io.autocrypt.jwlee.cowork.core.tools.BashTool;
import io.autocrypt.jwlee.cowork.core.tools.CoreWorkspaceProvider;
import io.autocrypt.jwlee.cowork.core.tools.CoworkLogger;
import io.autocrypt.jwlee.cowork.core.tools.FileReadTool;
import io.autocrypt.jwlee.cowork.core.tools.FileWriteTool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DocDiffAgent analyzes differences between two versions of technical documentation.
 * It follows the Map -> Diff -> Analyze strategy and saves results to the workspace.
 */
@Agent(description = "기술 문서의 버전 간 차이점(추가/삭제/변경)을 상세히 분석하여 보고서를 생성하는 에이전트")
@Component
public class DocDiffAgent {

    private final BashTool bashTool;
    private final FileReadTool fileReadTool;
    private final FileWriteTool fileWriteTool;
    private final CoreWorkspaceProvider workspaceProvider;
    private final CoworkLogger logger;
    private final PromptProvider promptProvider;

    public DocDiffAgent(BashTool bashTool, 
                        FileReadTool fileReadTool, 
                        FileWriteTool fileWriteTool,
                        CoreWorkspaceProvider workspaceProvider,
                        CoworkLogger logger, 
                        PromptProvider promptProvider) {
        this.bashTool = bashTool;
        this.fileReadTool = fileReadTool;
        this.fileWriteTool = fileWriteTool;
        this.workspaceProvider = workspaceProvider;
        this.logger = logger;
        this.promptProvider = promptProvider;
    }

    // --- Actions ---

    @Action
    public TOCResult extractTOC(DocVersion docVersion) throws IOException {
        logger.info("DocDiffAgent", "Extracting TOC for version: " + docVersion.version());
        
        String command = String.format("grep -nE '^#+ |^=+ |<h[1-6]' %s", docVersion.filePath());
        String resultJson = bashTool.execute(command);
        
        String stdout = "";
        try {
            Pattern p = Pattern.compile("\"stdout\":\"(.*?)\",\"message\"", Pattern.DOTALL);
            Matcher m = p.matcher(resultJson);
            if (m.find()) {
                stdout = m.group(1).replace("\\n", "\n").replace("\\\"", "\"");
            }
        } catch (Exception e) {
            logger.error("DocDiffAgent", "Failed to parse BashTool output", e);
        }

        List<TOCEntry> entries = new ArrayList<>();
        String[] lines = stdout.split("\n");
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                int colonIdx = line.indexOf(':');
                if (colonIdx == -1) continue;
                int lineNum = Integer.parseInt(line.substring(0, colonIdx));
                String content = line.substring(colonIdx + 1).trim();
                int level = 1;
                String title = content;
                if (content.startsWith("#")) {
                    level = 0; while (level < content.length() && content.charAt(level) == '#') level++;
                    title = content.substring(level).trim();
                } else if (content.startsWith("=")) {
                    level = 0; while (level < content.length() && content.charAt(level) == '=') level++;
                    title = content.substring(level).trim();
                }
                entries.add(new TOCEntry(title, level, lineNum, 0));
            } catch (Exception ignored) {}
        }

        List<TOCEntry> finalizedEntries = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            int nextStart = (i + 1 < entries.size()) ? entries.get(i + 1).startLine() - 1 : Integer.MAX_VALUE;
            TOCEntry current = entries.get(i);
            finalizedEntries.add(new TOCEntry(current.title(), current.level(), current.startLine(), nextStart));
        }
        return new TOCResult(finalizedEntries);
    }

    @Action
    public TOCMapResult mapTOCs(TOCResult sourceTOC, TOCResult targetTOC, DocVersion sourceVer, DocVersion targetVer, Ai ai) {
        String prompt = promptProvider.getPrompt("agents/docdiff/map-toc.jinja", Map.of(
                "sourceVersion", sourceVer.version(),
                "targetVersion", targetVer.version(),
                "sourceTOC", sourceTOC.entries(),
                "targetTOC", targetTOC.entries()
        ));
        return ai.withLlm(LlmOptions.withLlmForRole("normal")).creating(TOCMapResult.class).fromPrompt(prompt);
    }

    @Action
    public SectionDiff analyzeSectionContent(MappedSection mappedSection, DocVersion sourceVer, DocVersion targetVer, Ai ai) throws IOException {
        String sourceContent = fileReadTool.readFileWithRange(sourceVer.filePath(), mappedSection.source().startLine(), mappedSection.source().endLine()).content();
        String targetContent = fileReadTool.readFileWithRange(targetVer.filePath(), mappedSection.target().startLine(), mappedSection.target().endLine()).content();
        String prompt = promptProvider.getPrompt("agents/docdiff/compare-content.jinja", Map.of(
                "title", mappedSection.target().title(),
                "sourceContent", sourceContent,
                "targetContent", targetContent
        ));
        return ai.withLlm(LlmOptions.withLlmForRole("performant")).creating(SectionDiff.class).fromPrompt(prompt);
    }

    /**
     * Synthesizes the final report and saves it to the workspace artifacts directory.
     */
    @AchievesGoal(description = "기술 문서 버전 차이 분석 보고서 생성 및 워크스페이스 저장")
    @Action
    public DocDiffReport synthesizeFinalReport(
            TOCMapResult mapResult, 
            List<SectionDiff> sectionDiffs, 
            Ai ai) throws IOException {
        
        String prompt = promptProvider.getPrompt("agents/docdiff/synthesize-report.jinja", Map.of(
                "mapResult", mapResult,
                "sectionDiffs", sectionDiffs
        ));

        DocDiffReport report = ai.withLlm(LlmOptions.withLlmForRole("performant"))
                .creating(DocDiffReport.class)
                .fromPrompt(prompt);

        // --- Save to Workspace ---
        String workspaceId = "doc-diff-" + mapResult.sourceVersion() + "-to-" + mapResult.targetVersion();
        Path artifactPath = workspaceProvider.getSubPath("DocDiffAgent", workspaceId, CoreWorkspaceProvider.SubCategory.ARTIFACTS);
        String fileName = "diff-report.md";
        Path filePath = artifactPath.resolve(fileName);
        
        fileWriteTool.writeFile(filePath.toString(), report.content());
        logger.info("DocDiffAgent", "Report saved to: " + filePath.toAbsolutePath());

        return report;
    }
}
