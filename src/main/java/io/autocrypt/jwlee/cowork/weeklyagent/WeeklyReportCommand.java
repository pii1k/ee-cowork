package io.autocrypt.jwlee.cowork.weeklyagent;

import com.embabel.agent.core.AgentPlatform;
import io.autocrypt.jwlee.cowork.weeklyagent.dto.FinalWeeklyReport;
import io.autocrypt.jwlee.cowork.core.dto.JiraIssueInfo;
import io.autocrypt.jwlee.cowork.weeklyagent.dto.JiraIssueList;
import io.autocrypt.jwlee.cowork.core.dto.MeetingInfo;
import io.autocrypt.jwlee.cowork.weeklyagent.dto.RawWeeklyData;
import io.autocrypt.jwlee.cowork.core.tools.ConfluenceService;
import io.autocrypt.jwlee.cowork.core.tools.JiraService;
import io.autocrypt.jwlee.cowork.core.tools.RealConfluenceService;
import io.autocrypt.jwlee.cowork.core.commands.BaseAgentCommand;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.List;
import java.util.concurrent.ExecutionException;

@ShellComponent
public class WeeklyReportCommand extends BaseAgentCommand {

    private final ConfluenceService confluenceService;
    private final JiraService jiraService;

    public WeeklyReportCommand(AgentPlatform agentPlatform, ConfluenceService confluenceService, JiraService jiraService) {
        super(agentPlatform);
        this.confluenceService = confluenceService;
        this.jiraService = jiraService;
    }

    @ShellMethod(value = "주간보고서를 자동 생성합니다.", key = "generate-weekly")
    public String generateWeekly(
            @ShellOption(defaultValue = "") String meetingPageId,
            @ShellOption(value = {"-p", "--show-prompts"}, defaultValue = "false", help = "Log prompts sent to the LLM") boolean p,
            @ShellOption(value = {"-r", "--show-responses"}, defaultValue = "false", help = "Log LLM responses") boolean r) throws ExecutionException, InterruptedException {
        System.out.println("\n[System] 주간보고서 생성 준비 중...");
        
        String finalMeetingId = meetingPageId;
        if (finalMeetingId.isEmpty()) {
            List<MeetingInfo> meetings = confluenceService.getRecentMeetingUrls();
            if (meetings.isEmpty()) {
                return "[Error] 최근 회의록을 찾을 수 없습니다. confluence 설정 및 API 토큰을 확인하세요.";
            }
            finalMeetingId = meetings.get(0).id();
            System.out.println("[System] 최근 회의록 자동 선택: " + meetings.get(0).title());
        }
        
        System.out.println("[System] Confluence 및 Jira 데이터 수집 중...");
        String okrHtml = "";
        String meetingHtml = "";
        try {
            if (confluenceService instanceof RealConfluenceService rcs) {
                okrHtml = confluenceService.getPageStorage(rcs.getOkrPageId());
            } else {
                okrHtml = confluenceService.getPageStorage("mock-okr");
            }
            meetingHtml = confluenceService.getPageStorage(finalMeetingId);
        } catch (Exception e) {
            System.out.println("[Warning] Confluence 데이터 수집 실패. 에이전트는 빈 데이터로 분석을 시도합니다.");
        }
        
        RawWeeklyData rawData = new RawWeeklyData(okrHtml, meetingHtml);
        
        List<JiraIssueInfo> jiraIssues;
        try {
            jiraIssues = jiraService.readIssues("-1w");
        } catch (Exception e) {
            jiraIssues = List.of();
            System.out.println("[Warning] Jira 데이터 수집 실패. 빈 데이터로 진행합니다.");
        }

        System.out.println("[System] 에이전트 분석 시작...");
        FinalWeeklyReport result = invokeAgent(
                FinalWeeklyReport.class,
                getOptions(p, r),
                rawData, 
                new JiraIssueList(jiraIssues)
        );

        if (result != null) {
            return "[System] 주간보고서 생성 완료!\n================================\n[공지/공유사항]\n" + result.noticeHtml() + "\n\n[요청/대기사항]\n" + result.requestHtml() + "\n================================";
        } else {
            return "[System] 작업이 중단되었거나 최종 결과가 없습니다.";
        }
    }
}
