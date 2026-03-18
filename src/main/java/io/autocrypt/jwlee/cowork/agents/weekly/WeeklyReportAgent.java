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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Agent(description = "주간보고서 생성 및 검토 에이전트")
@Component
public class WeeklyReportAgent {

    private final RoleGoalBackstory analystPersona;
    private final RoleGoalBackstory collectorPersona;

    public WeeklyReportAgent() {
        this.analystPersona = new RoleGoalBackstory(
            "Research Director / Director",
            "Draft a comprehensive weekly report by analyzing OKRs, team reports, and Jira issues.",
            "You manage a 30-person SW development team. You are meticulous, strategic, and focused on OKR alignment."
        );
        this.collectorPersona = new RoleGoalBackstory(
            "Administrative Assistant",
            "Accurately extract and summarize relevant data for specific teams.",
            "You are a meticulous administrative assistant. Your job is to accurately extract and summarize team data without adding any personal judgment or evaluation."
        );
    }

    private static final FinalWeeklyReport REPORT_EXAMPLE = new FinalWeeklyReport(
        "<h3>개발그룹</h3><h4>사업 지원</h4><ul><li><b>[하만/볼보 트럭 (V2X-EE)]</b> 내용</li></ul>",
        "<ul><li>N/A</li></ul>"
    );

    @State
    public interface Stage {}

    public record TeamOpinion(String teamName, String opinion) {}
    public record TeamOpinionList(List<TeamOpinion> opinions) {}

    @Action
    public AnalyzeTeamsState start(RawWeeklyData rawData, JiraIssueList jiraIssueList, Ai ai, ActionContext ctx) {
        List<String> targetTeams = List.of("EE팀", "BE팀", "PKI팀", "PnC팀", "FE팀", "Engineering팀");
        
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

            String collectPrompt = String.format("""
                당신은 [%s] 전담 행정 총무입니다. 제공된 데이터에서 [%s]과 관련된 내용만 발췌하세요.
                
                # 원본 데이터:
                <OKR>%s</OKR>
                <MEETING>%s</MEETING>
                """, team, team, rawData.okrHtml(), rawData.meetingHtml());

            TeamSummary summary = ai.withAutoLlm().withPromptContributor(collectorPersona)
                    .creating(TeamSummary.class).fromPrompt(collectPrompt);

            return new TeamAnalysis(team, summary.currentOkr(), summary.currentMeetingIssues(), filteredJiraIssues, "");
        }).toList();

        List<TeamAnalysis> analyses = evaluateTeams(collectedData, null, ai, this.analystPersona);
        analyses.forEach(ctx::addObject);
        return new AnalyzeTeamsState(rawData, jiraIssueList, analyses, this.analystPersona);
    }

    private static List<TeamAnalysis> evaluateTeams(List<TeamAnalysis> extractedData, String feedback, Ai ai, RoleGoalBackstory analystPersona) {
        String dataText = extractedData.stream()
            .map(d -> String.format("팀명: [%s]\n- OKR: %s\n- 회의록: %s\n- Jira: %s\n", d.teamName(), d.currentOkr(), d.currentMeetingIssues(), d.currentJiraIssues()))
            .collect(Collectors.joining("\n---\n"));

        String prompt = String.format("""
            연구소장으로서 각 팀의 주간 성과를 진단하세요.
            # 팀별 데이터:
            %s
            %s
            """, dataText, feedback != null ? "\n# 이전 피드백 반영 지시:\n" + feedback : "");

        TeamOpinionList opinionList = ai.withAutoLlm().withPromptContributor(analystPersona)
                .creating(TeamOpinionList.class).fromPrompt(prompt);

        return extractedData.stream().map(d -> {
            String op = opinionList.opinions().stream()
                    .filter(o -> o.teamName().equalsIgnoreCase(d.teamName()) || d.teamName().contains(o.teamName()))
                    .map(TeamOpinion::opinion).findFirst().orElse("분석 의견을 생성하지 못했습니다.");
            return new TeamAnalysis(d.teamName(), d.currentOkr(), d.currentMeetingIssues(), d.currentJiraIssues(), op);
        }).toList();
    }

    @State
    public static record AnalyzeTeamsState(RawWeeklyData rawData, JiraIssueList jiraIssues, List<TeamAnalysis> analyses, RoleGoalBackstory analystPersona) implements Stage {
        @Action
        public ApprovalDecision waitForApproval(ActionContext ctx) {
            String processId = ctx.getProcessContext().getAgentProcess().getId();
            StringBuilder sb = new StringBuilder();
            analyses.forEach(a -> sb.append("팀: ").append(a.teamName()).append("\n- 의견: ").append(a.aiOpinion()).append("\n\n"));
            
            ApplicationContextHolder.getPublisher().publishEvent(
                new ApprovalRequestedEvent(processId, "팀별 분석 내용을 검토하고 승인해주세요.", sb.toString())
            );
            return WaitFor.formSubmission("Approval Event Published", ApprovalDecision.class);
        }

        @Action(clearBlackboard = true)
        public Stage processFeedback(ApprovalDecision decision, Ai ai, ActionContext ctx) {
            if (decision.approved()) {
                String prompt = "승인된 팀별 데이터를 통합하여 최종 주간보고서 HTML을 작성하세요.\n\n# 데이터:\n" + analyses;
                FinalWeeklyReport finalReport = ai.withAutoLlm().withPromptContributor(analystPersona)
                        .creating(FinalWeeklyReport.class).withExample("최종 주간보고 구조 예시", REPORT_EXAMPLE).fromPrompt(prompt);
                return new FinalizeReportState(finalReport, analyses, analystPersona);
            } else {
                List<TeamAnalysis> reEvaluated = evaluateTeams(analyses, decision.comment(), ai, analystPersona);
                reEvaluated.forEach(ctx::addObject);
                return new AnalyzeTeamsState(rawData, jiraIssues, reEvaluated, analystPersona);
            }
        }
    }

    @State
    public static record FinalizeReportState(FinalWeeklyReport report, List<TeamAnalysis> analyses, RoleGoalBackstory analystPersona) implements Stage {
        @Action
        public ApprovalDecision waitForFinalApproval(ActionContext ctx) {
            String processId = ctx.getProcessContext().getAgentProcess().getId();
            String preview = "Notice:\n" + report.noticeHtml() + "\n\nRequest:\n" + report.requestHtml();
            ApplicationContextHolder.getPublisher().publishEvent(
                new ApprovalRequestedEvent(processId, "최종 보고서 초안을 검토해주세요.", preview)
            );
            return WaitFor.formSubmission("Approval Event Published", ApprovalDecision.class);
        }

        @Action(clearBlackboard = true)
        public Stage finalize(ApprovalDecision decision, Ai ai) {
            if (decision.approved()) {
                return new FinishedState(report, analyses);
            } else {
                String prompt = String.format("사용자 피드백을 반영하여 보고서를 수정하세요.\n\n# 피드백: %s\n\n# 현재 내용: %s", decision.comment(), report);
                FinalWeeklyReport revised = ai.withAutoLlm().withPromptContributor(analystPersona)
                        .creating(FinalWeeklyReport.class).withExample("주간보고 형식 유지 예시", REPORT_EXAMPLE).fromPrompt(prompt);
                return new FinalizeReportState(revised, analyses, analystPersona);
            }
        }
    }

    @State
    public static record FinishedState(FinalWeeklyReport finalReport, List<TeamAnalysis> analyses) implements Stage {
        @Action
        @AchievesGoal(description = "주간보고서가 최종 승인됨")
        public FinalWeeklyReport done() {
            return finalReport;
        }
    }
}
