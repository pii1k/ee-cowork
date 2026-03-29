package io.autocrypt.jwlee.cowork.morningbriefingagent;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.AgentProcess;
import com.embabel.common.ai.model.LlmOptions;

import io.autocrypt.jwlee.cowork.core.dto.JiraIssueInfo;
import io.autocrypt.jwlee.cowork.core.hitl.ApplicationContextHolder;
import io.autocrypt.jwlee.cowork.core.hitl.NotificationEvent;
import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;
import io.autocrypt.jwlee.cowork.core.tools.ConfluenceService;
import io.autocrypt.jwlee.cowork.core.tools.CoreFileTools;
import io.autocrypt.jwlee.cowork.core.tools.CoreWorkspaceProvider;
import io.autocrypt.jwlee.cowork.core.tools.CoworkLogger;
import io.autocrypt.jwlee.cowork.core.tools.JiraService;
import io.autocrypt.jwlee.cowork.morningbriefingagent.MorningBriefingAgent.JiraChange;

@Agent(description = "Analyzes yesterday's Jira and Confluence updates to generate a morning briefing.")
@Component
public class MorningBriefingAgent {

    public record JiraChange(String issueKey, String summary, String oldStatus, String newStatus, String assignee) {}
    public record MeetingNote(String title, String rawContent, String author) {}
    public record MeetingSummary(String title, List<String> keyDecisions, List<String> actionItems) {}
    public record MeetingSummaryList(List<MeetingSummary> summaries) {}
    
    public record MorningBriefingReport(String content) {}
    public record BriefingRequest(String targetDate) {}

    @State
    public interface Stage {}

    @State
    public record BriefingPreparationState(String targetDate, List<JiraChange> jiraChanges, List<MeetingNote> rawMeetingNotes) implements Stage {}

    @State
    public record SynthesisState(String targetDate, List<JiraChange> jiraChanges, List<MeetingSummary> meetingSummaries) implements Stage {}

    private final JiraService jiraService;
    private final ConfluenceService confluenceService;
    private final PromptProvider promptProvider;
    private final CoreWorkspaceProvider workspaceProvider;
    private final CoreFileTools fileTools;
    private final CoworkLogger logger;

    public MorningBriefingAgent(JiraService jiraService, ConfluenceService confluenceService, 
                               PromptProvider promptProvider, CoreWorkspaceProvider workspaceProvider, 
                               CoreFileTools fileTools, CoworkLogger logger) {
        this.jiraService = jiraService;
        this.confluenceService = confluenceService;
        this.promptProvider = promptProvider;
        this.workspaceProvider = workspaceProvider;
        this.fileTools = fileTools;
        this.logger = logger;
    }

    @Action
    public BriefingPreparationState gatherYesterdayData(BriefingRequest req) throws IOException {
        String targetDate = req.targetDate() == null || req.targetDate().isEmpty() 
            ? getLastBusinessDay(LocalDate.now(ZoneId.of("Asia/Seoul"))).toString() 
            : req.targetDate();
        
        logger.info("MorningBriefing", "데이터 수집 시작 (Target: " + targetDate + ")");

        List<JiraIssueInfo> rawIssues = jiraService.readIssues(targetDate);
        List<JiraChange> jiraChanges = rawIssues.stream()
                .limit(20)
                .map(i -> new JiraChange(i.key(), i.summary(), "UNKNOWN", i.status(), i.assignee()))
                .collect(Collectors.toList());

        ConfluenceService.ConfluencePageInfo reportInfo = confluenceService.getCurrentWeeklyReport();
        List<MeetingNote> rawNotes = new java.util.ArrayList<>();
        if (!reportInfo.isEmpty()) {
            String processedContent = reportInfo.content();
            if (reportInfo.title().contains("주간 팀장회의록")) {
                logger.info("MorningBriefing", "주간 팀장회의록 감지. '팀별 주간보고' 섹션 추출 중...");
                processedContent = extractWeeklyReports(reportInfo.content());
                logger.info("MorningBriefing", "[EXTRACTED SECTION]\n" + (processedContent.length() > 300 ? processedContent.substring(0, 300) + "..." : processedContent));
            }
            rawNotes.add(new MeetingNote(reportInfo.title(), processedContent, "System"));
        }

        return new BriefingPreparationState(targetDate, jiraChanges, rawNotes);
    }

    private LocalDate getLastBusinessDay(LocalDate today) {
        DayOfWeek dow = today.getDayOfWeek();
        if (dow == DayOfWeek.MONDAY) return today.minusDays(3);
        if (dow == DayOfWeek.SUNDAY) return today.minusDays(2);
        if (dow == DayOfWeek.SATURDAY) return today.minusDays(1);
        return today.minusDays(1);
    }

    private String extractWeeklyReports(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        Elements allElements = doc.body().children();
        
        Element targetHeader = null;
        for (Element el : allElements) {
            if (el.tagName().matches("h[1-6]") && el.text().contains("팀별 주간보고")) {
                targetHeader = el;
                break;
            }
        }

        if (targetHeader == null) {
            return html;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(targetHeader.outerHtml()).append("\n");
        
        int targetLevel = Integer.parseInt(targetHeader.tagName().substring(1));
        
        Element next = targetHeader.nextElementSibling();
        while (next != null) {
            // 1. 같은 레벨 이상의 헤더를 만나면 종료 (예: 다른 h2)
            if (next.tagName().matches("h[1-6]")) {
                int nextLevel = Integer.parseInt(next.tagName().substring(1));
                if (nextLevel <= targetLevel) {
                    break;
                }
            }
            
            // 2. 수평선(<hr>)을 만나면 섹션이 끝난 것으로 간주하고 종료
            if (next.tagName().equalsIgnoreCase("hr")) {
                break;
            }

            sb.append(next.outerHtml()).append("\n");
            next = next.nextElementSibling();
        }
        
        return sb.toString();
    }

    @Action
    public SynthesisState summarizeMeetings(BriefingPreparationState state, Ai ai) {
        if (state.rawMeetingNotes().isEmpty()) {
            return new SynthesisState(state.targetDate(), state.jiraChanges(), List.of());
        }

        logger.info("MorningBriefing", "회의록 요약 중...");
        String prompt = promptProvider.getPrompt("agents/morningbriefing/summarize_meetings.jinja", Map.of(
            "targetDate", state.targetDate(),
            "notes", state.rawMeetingNotes()
        ));

         MeetingSummaryList summarized = ai.withLlm(LlmOptions.withLlmForRole("normal").withoutThinking())
                .creating(MeetingSummaryList.class)
                .fromPrompt(prompt);

        return new SynthesisState(state.targetDate(), state.jiraChanges(), summarized.summaries());
    }

    @AchievesGoal(description = "Generate final morning briefing report")
    @Action
    public MorningBriefingReport generateBriefingAndTasks(SynthesisState state, Ai ai, OperationContext ctx) {
        logger.info("MorningBriefing", "최종 마크다운 리포트 생성 중...");
        
        String prompt = promptProvider.getPrompt("agents/morningbriefing/generate_tasks.jinja", Map.of(
            "reportDate", state.targetDate(),
            "jiraChanges", state.jiraChanges(),
            "meetingSummaries", state.meetingSummaries()
        ));

        String markdown = ai.withLlm(LlmOptions.withLlmForRole("performant").withoutThinking().withMaxTokens(65536))
                .generateText(prompt);

        // 알림으로 리포트 전문 전송
        ApplicationContextHolder.getPublisher().publishEvent(
            new NotificationEvent("Morning Briefing (" + state.targetDate() + ")", markdown)
        );
        
        logger.info("MorningBriefing", "브리핑 생성 및 알림 완료");

        AgentProcess process = ctx.getAgentProcess();
        
        logger.info("MorningBriefing", "[Process Cost]\n" + process.costInfoString(false));

        var history = process.getHistory();
        String historyLog = String.format("Action history (%d actions):\n%s",
                history.size(),
                IntStream.range(0, history.size())
                        .mapToObj(i -> String.format("%d. %s (%.1fs)", 
                                i + 1, 
                                history.get(i).getActionName(), 
                                history.get(i).getRunningTime().toMillis() / 1000.0))
                        .collect(Collectors.joining("\n")));
        logger.info("MorningBriefing", "[Process History]\n" + historyLog);

        return new MorningBriefingReport(markdown);
    }
}
