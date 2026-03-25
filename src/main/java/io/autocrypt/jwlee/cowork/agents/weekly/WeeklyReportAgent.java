package io.autocrypt.jwlee.cowork.agents.weekly;

import com.embabel.agent.api.annotation.*;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.hitl.WaitFor;
import com.embabel.agent.prompt.persona.RoleGoalBackstory;
import io.autocrypt.jwlee.cowork.agents.weekly.dto.*;
import io.autocrypt.jwlee.cowork.core.hitl.ApprovalDecision;
import io.autocrypt.jwlee.cowork.core.hitl.ApprovalRequestedEvent;
import io.autocrypt.jwlee.cowork.core.hitl.ApplicationContextHolder;
import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;
import io.autocrypt.jwlee.cowork.core.tools.CoreFileTools;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Agent(description = "주간보고서 생성 및 검토 에이전트")
@Component
public class WeeklyReportAgent {

    private final RoleGoalBackstory analystPersona;
    private final RoleGoalBackstory collectorPersona;
    private final CoreFileTools fileTools;
    private final PromptProvider promptProvider;

    public WeeklyReportAgent(CoreFileTools fileTools, PromptProvider promptProvider) {
        this.fileTools = fileTools;
        this.promptProvider = promptProvider;
        this.analystPersona = promptProvider.getPersona("agents/weekly/persona-analyst.md");
        this.collectorPersona = promptProvider.getPersona("agents/weekly/persona-collector.md");
    }

    private static final FinalWeeklyReport REPORT_EXAMPLE = new FinalWeeklyReport(
        """
        ### 개발그룹
        #### 사업 지원
        - **[하만/볼보 트럭 (V2X-EE)]** LCM 유럽향 CMI 모듈 마이그레이션 : ~02/27 (20%)
        - **[이동의자유 (BE, FE)]** 기사앱 통합 테스트 및 CD 구성 : ~03/13 (70%)
        #### 내부 개발
        - **[PKI-Vehicles (PKI)]** KMS 연동 및 성능 테스트 : ~03/06 (30%)
        ### 엔지니어링팀
        - **[LX공사 (Eng)]** OEM PKI 및 키 주입 시스템 SaaS 제공 : ~2028/11/30 (3년)
        """,
        "- N/A"
    );

    @State
    public interface Stage {}

    public record TeamOpinions(
        String eeTeamOpinion,
        String beTeamOpinion,
        String pkiTeamOpinion,
        String pncTeamOpinion,
        String feTeamOpinion,
        String engTeamOpinion
    ) {
        public String getOpinion(String teamName) {
            return switch (teamName) {
                case "EE팀" -> eeTeamOpinion;
                case "BE팀" -> beTeamOpinion;
                case "PKI팀" -> pkiTeamOpinion;
                case "PnC팀" -> pncTeamOpinion;
                case "FE팀" -> feTeamOpinion;
                case "Eng팀" -> engTeamOpinion;
                default -> "분석 의견을 생성하지 못했습니다.";
            };
        }
    }

    @Action
    public AnalyzeTeamsState start(RawWeeklyData rawData, JiraIssueList jiraIssueList, Ai ai, ActionContext ctx) {
        List<String> targetTeams = List.of("EE팀", "BE팀", "PKI팀", "PnC팀", "FE팀", "Eng팀");
        
        List<TeamAnalysis> collectedData = targetTeams.parallelStream().map(team -> {
            String teamKey = team.replace("팀", "");
            var teamIssuesList = jiraIssueList.issues().stream()
                    .filter(i -> {
                        String comp = i.component().toUpperCase();
                        return comp.contains(teamKey.toUpperCase()) || comp.contains(team.toUpperCase()) || (teamKey.equals("Engineering") && comp.contains("ENG"));
                    })
                    .filter(i -> !"To Do".equalsIgnoreCase(i.status()))
                    .map(i -> String.format("[%s] %s (담당자: %s, 상태: %s)", i.key(), i.summary(), i.assignee(), i.status()))
                    .collect(Collectors.toList());
            
            String filteredJiraIssues = teamIssuesList.isEmpty() ? "N/A" : String.join("\n", teamIssuesList);

            String collectPrompt = promptProvider.getPrompt("agents/weekly/collect-team-data.jinja", Map.of(
                "team", team,
                "teamKey", teamKey,
                "okrHtml", rawData.okrHtml(),
                "meetingHtml", rawData.meetingHtml()
            ));

            TeamSummary summary = ai.withLlmByRole("simple").withPromptContributor(collectorPersona)
                    .creating(TeamSummary.class).fromPrompt(collectPrompt);

            return new TeamAnalysis(team, summary.currentOkr(), summary.currentMeetingIssues(), filteredJiraIssues, "");
        }).toList();

        List<TeamAnalysis> analyses = evaluateTeams(collectedData, null, "performant", ai, this.analystPersona, this.promptProvider);
        analyses.forEach(ctx::addObject);
        return new AnalyzeTeamsState(rawData, jiraIssueList, analyses, this.analystPersona, this.fileTools, this.promptProvider);
    }

    private static List<TeamAnalysis> evaluateTeams(List<TeamAnalysis> extractedData, String feedback, String role, Ai ai, RoleGoalBackstory analystPersona, PromptProvider promptProvider) {
        String dataText = extractedData.stream()
            .map(d -> String.format("팀명: [%s]\n- OKR: %s\n- 회의록: %s\n- Jira: %s\n", d.teamName(), d.currentOkr(), d.currentMeetingIssues(), d.currentJiraIssues()))
            .collect(Collectors.joining("\n---\n"));

        String prompt = promptProvider.getPrompt("agents/weekly/evaluate-teams.jinja", Map.of(
            "dataText", dataText,
            "feedback", feedback != null ? feedback : ""
        ));

        TeamOpinions opinions = ai.withLlmByRole(role).withPromptContributor(analystPersona)
                .creating(TeamOpinions.class).fromPrompt(prompt);

        return extractedData.stream().map(d -> {
            String op = opinions.getOpinion(d.teamName());
            if (op == null || op.isBlank()) {
                System.err.println("[BUG] 분석 의견 누락 발생! 팀명: " + d.teamName());
                op = "분석 의견을 생성하지 못했습니다.";
            }
            return new TeamAnalysis(d.teamName(), d.currentOkr(), d.currentMeetingIssues(), d.currentJiraIssues(), op);
        }).toList();
    }

    @State
    public static record AnalyzeTeamsState(RawWeeklyData rawData, JiraIssueList jiraIssues, List<TeamAnalysis> analyses, RoleGoalBackstory analystPersona, CoreFileTools fileTools, PromptProvider promptProvider) implements Stage {
        @Action
        public ApprovalDecision waitForApproval(ActionContext ctx) {
            String processId = ctx.getProcessContext().getAgentProcess().getId();
            StringBuilder sb = new StringBuilder();
            analyses.forEach(a -> sb.append("팀: ").append(a.teamName()).append("\n- 의견: ").append(a.aiOpinion()).append("\n\n"));
            
            ApplicationContextHolder.getPublisher().publishEvent(
                new ApprovalRequestedEvent(processId, "팀별 분석 내용을 검토하고 승인해주세요.", sb.toString(), true)
            );
            return WaitFor.formSubmission("Approval Event Published", ApprovalDecision.class);
        }

        @Action(clearBlackboard = true)
        public Stage processFeedback(ApprovalDecision decision, Ai ai, ActionContext ctx) {
            if (decision.approved()) {
                String prompt = promptProvider.getPrompt("agents/weekly/finalize-report.jinja", Map.of(
                    "analyses", analyses
                ));

                FinalWeeklyReport finalReport = ai.withLlmByRole("performant").withPromptContributor(analystPersona)
                        .creating(FinalWeeklyReport.class).withExample("최종 주간보고 구조 예시", REPORT_EXAMPLE).fromPrompt(prompt);
                return new FinalizeReportState(finalReport, analyses, analystPersona, fileTools, promptProvider);
            } else {
                List<TeamAnalysis> reEvaluated = evaluateTeams(analyses, decision.comment(), "normal", ai, analystPersona, promptProvider);
                reEvaluated.forEach(ctx::addObject);
                return new AnalyzeTeamsState(rawData, jiraIssues, reEvaluated, analystPersona, fileTools, promptProvider);
            }
        }
    }

    @State
    public static record FinalizeReportState(FinalWeeklyReport report, List<TeamAnalysis> analyses, RoleGoalBackstory analystPersona, CoreFileTools fileTools, PromptProvider promptProvider) implements Stage {
        @Action
        public ApprovalDecision waitForFinalApproval(ActionContext ctx) {
            String processId = ctx.getProcessContext().getAgentProcess().getId();
            String preview = "Notice (Markdown):\n" + report.noticeHtml() + "\n\nRequest (Markdown):\n" + report.requestHtml();
            ApplicationContextHolder.getPublisher().publishEvent(
                new ApprovalRequestedEvent(processId, "최종 보고서 초안을 검토해주세요.", preview)
            );
            return WaitFor.formSubmission("Approval Event Published", ApprovalDecision.class);
        }

        @Action(clearBlackboard = true)
        public Stage finalize(ApprovalDecision decision, Ai ai) {
            if (decision.approved()) {
                return new FinishedState(report, analyses, fileTools);
            } else {
                String prompt = promptProvider.getPrompt("agents/weekly/revise-report.jinja", Map.of(
                    "feedback", decision.comment(),
                    "currentReport", report
                ));
                FinalWeeklyReport revised = ai.withLlmByRole("normal").withPromptContributor(analystPersona)
                        .creating(FinalWeeklyReport.class).withExample("주간보고 형식 유지 예시", REPORT_EXAMPLE).fromPrompt(prompt);
                return new FinalizeReportState(revised, analyses, analystPersona, fileTools, promptProvider);
            }
        }
    }

    @State
    public static record FinishedState(FinalWeeklyReport finalReport, List<TeamAnalysis> analyses, CoreFileTools fileTools) implements Stage {
        @Action
        @AchievesGoal(description = "주간보고서가 최종 승인됨")
        public FinalWeeklyReport done() {
            String dateStr = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String filename = "weekly-report." + dateStr + ".md";
            
            StringBuilder sb = new StringBuilder();
            sb.append("# 주간보고서 (").append(LocalDate.now(ZoneId.of("Asia/Seoul"))).append(")\n\n");
            
            sb.append("## 1. 최종 요약\n\n");
            sb.append("### 공지/공유사항\n").append(finalReport.noticeHtml()).append("\n\n");
            sb.append("### 요청/대기사항\n").append(finalReport.requestHtml()).append("\n\n");
            
            sb.append("---\n\n## 2. 팀별 상세 분석 및 중간 데이터\n\n");
            for (TeamAnalysis a : analyses) {
                sb.append("### [").append(a.teamName()).append("]\n");
                sb.append("**1. OKR 요약**\n").append(a.currentOkr()).append("\n\n");
                sb.append("**2. 회의록 요약**\n").append(a.currentMeetingIssues()).append("\n\n");
                sb.append("**3. Jira 이슈 현황**\n").append(a.currentJiraIssues()).append("\n\n");
                sb.append("**4. 연구소장 진단 의견**\n> ").append(a.aiOpinion().replace("\n", "\n> ")).append("\n\n");
            }
            
            try {
                fileTools.saveGeneratedContent(filename, sb.toString());
            } catch (Exception e) {
                System.err.println("보고서 저장 실패: " + e.getMessage());
            }
            return finalReport;
        }
    }
}
