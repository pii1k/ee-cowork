package io.autocrypt.jwlee.cowork.weeklyreport.agent;

import com.embabel.agent.api.annotation.*;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.hitl.WaitFor;
import com.embabel.common.ai.model.LlmOptions;
import io.autocrypt.jwlee.cowork.weeklyreport.dto.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Agent(description = "주간보고서 생성 및 검토 에이전트")
@Component
public class WeeklyReportAgent {

    @State
    public interface Stage {}

    /**
     * 1단계: 팀별 데이터 수합 및 AI 분석
     */
    @Action
    public AnalyzeTeamsState start(OkrInfo okr, TeamReportList teamReportList, JiraIssueList jiraIssueList, Ai ai, ActionContext ctx) {
        return performTeamAnalysis(okr, teamReportList.reports(), jiraIssueList.issues(), null, ai, ctx);
    }

    private static AnalyzeTeamsState performTeamAnalysis(OkrInfo okr, List<TeamReportInfo> teamReports, List<JiraIssueInfo> jiraIssues, String feedback, Ai ai, ActionContext ctx) {
        List<String> teams = jiraIssues.stream()
                .map(JiraIssueInfo::component)
                .distinct()
                .collect(Collectors.toList());
                
        if (!teams.contains("Eng")) {
            teams.add("Eng");
        }

        List<TeamAnalysis> analyses = teams.stream().map(team -> {
            var teamIssues = jiraIssues.stream()
                    .filter(i -> i.component().equals(team))
                    .filter(i -> !"To Do".equalsIgnoreCase(i.status()))
                    .toList();
                    
            String rawJiraIssues = teamIssues.isEmpty() ? "N/A" : teamIssues.stream()
                    .map(i -> String.format("[%s] %s (담당자: %s, 상태: %s)", i.key(), i.summary(), i.assignee(), i.status()))
                    .collect(Collectors.joining("\n"));

            String specialInstruction = team.equals("Eng") 
                ? "\n# 특별 지시: Eng(엔지니어링) 팀은 Jira를 사용하지 않습니다. Jira 이슈 항목은 'N/A'로 기입하세요." 
                : "";

            String meetingText = teamReports.stream()
                    .map(TeamReportInfo::content)
                    .collect(Collectors.joining("\n"));

            String summaryPrompt = String.format("""
                주어진 전체 데이터에서 오직 [%s] 팀과 관련된 내용만 찾아 추출 및 요약하세요.
                이름이 정확히 일치하지 않더라도, 맥락상 해당 팀의 업무로 보이는 것을 모두 찾으세요 (예: CAM.PKI -> PKI팀, PKI).
                단, '개발그룹'과 같은 상위 조직명은 특정 팀을 지칭하지 않으므로 무시하세요.
                
                # 전체 분기 OKR 데이터:
                %s
                
                # 전체 주간회의록(팀별 보고사항):
                (주의: 회의록 데이터 내에 각 팀별 '진행 업무 중 주요사항', '주요 계획' 섹션이 있습니다. 이 내용들을 반드시 찾아 모두 포함시키세요!)
                %s
                
                # 해당 팀의 Jira 이슈 (절대 축약하지 말고 이 내용을 그대로 모두 복사해서 currentJiraIssues 필드에 넣을 것):
                %s
                %s
                """, team, okr.objectives(), meetingText, rawJiraIssues, specialInstruction);

            TeamSummary summary = ai.withLlmByRole("simple")
                    .creating(TeamSummary.class)
                    .fromPrompt(summaryPrompt);
                    
            System.out.println("========== [" + team + "] Simple Model Extraction ==========");
            System.out.println("Extracted OKR:\n" + summary.currentOkr());
            System.out.println("Extracted Meeting Issues:\n" + summary.currentMeetingIssues());
            System.out.println("Extracted Jira Issues:\n" + summary.currentJiraIssues());
            System.out.println("==========================================================");

            String opinionPrompt = String.format("""
                다음은 [%s] 팀의 주간 현황 요약입니다.
                
                - 현재 OKR:
                %s
                
                - 현재 진행중인 이슈(회의록):
                %s
                
                - 현재 Jira 이슈:
                %s
                
                위 내용을 바탕으로 이 팀의 성과, 업무 집중도, 리스크, 향후 집중해야 할 방향에 대한 종합적인 AI 분석 의견(1~2단락)을 작성하세요.
                %s
                """, team, summary.currentOkr(), summary.currentMeetingIssues(), summary.currentJiraIssues(),
                feedback != null ? "\n# 이전 피드백 반영 지시:\n" + feedback : "");

            String opinion = ai.withLlmByRole("normal")
                    .generateText(opinionPrompt);

            TeamAnalysis analysis = new TeamAnalysis(team, summary.currentOkr(), summary.currentMeetingIssues(), summary.currentJiraIssues(), opinion);
            
            ctx.addObject(analysis); 
            return analysis;
        }).toList();

        return new AnalyzeTeamsState(okr, teamReports, jiraIssues, analyses);
    }

    /**
     * 2단계: 팀별 분석 결과 검토 (WAITING)
     */
    @State
    public record AnalyzeTeamsState(OkrInfo okr, List<TeamReportInfo> teamReports, List<JiraIssueInfo> jiraIssues, List<TeamAnalysis> analyses) implements Stage {
        @Action
        public HumanFeedback waitForApproval() {
            return WaitFor.formSubmission("팀별 분석 내용을 검토하고 승인해주세요.", HumanFeedback.class);
        }

        @Action(clearBlackboard = true)
        public Stage processFeedback(HumanFeedback feedback, Ai ai, ActionContext ctx) {
            if (feedback.approved()) {
                FinalWeeklyReport example = new FinalWeeklyReport(
                    "<h3>공지/공유사항</h3><ul><li>[개발그룹] 사업 지원...</li></ul>", 
                    "<h3>요청/대기사항</h3><ul><li>N/A</li></ul>"
                );

                String prompt = "승인된 팀별 분석 결과를 바탕으로 최종 주간보고서 HTML을 작성하세요.\n" +
                        "# 중요 지침:\n" +
                        "- 반드시 아래 제공된 '주간보고 형식 예시'의 HTML 구조(<ul>, <li> 트리 형태)를 엄격하게 유지하세요.\n" +
                        "- '요청/대기사항' 섹션은 억지로 내용을 적지 마세요.\n" +
                        "- 타 부서(팀)가 명시된, 요청이 시급한 사안이 분석 내용에 명확히 존재하는 경우가 아니라면 가급적 'N/A'로 작성하세요.\n\n" + 
                        "# 팀별 분석 결과:\n" + analyses;

                FinalWeeklyReport finalReport = ai.withLlmByRole("performant")
                        .creating(FinalWeeklyReport.class)
                        .withExample("주간보고 형식 예시", example)
                        .fromPrompt(prompt);

                return new FinalizeReportState(finalReport, okr, teamReports, jiraIssues, analyses);
            } else {
                return performTeamAnalysis(okr, teamReports, jiraIssues, feedback.comments(), ai, ctx);
            }
        }
    }

    /**
     * 3단계: 최종 보고서 초안 검토 (WAITING)
     */
    @State
    public record FinalizeReportState(FinalWeeklyReport report, OkrInfo okr, List<TeamReportInfo> teamReports, List<JiraIssueInfo> jiraIssues, List<TeamAnalysis> analyses) implements Stage {
        @Action
        public HumanFeedback waitForFinalApproval() {
            return WaitFor.formSubmission("최종 보고서 초안을 검토해주세요.", HumanFeedback.class);
        }

        @Action(clearBlackboard = true)
        public Stage finalize(HumanFeedback feedback, Ai ai) {
            if (feedback.approved()) {
                return new FinishedState(report, analyses);
            } else {
                String prompt = String.format("피드백 반영: %s\n\n현재 내용: %s", feedback.comments(), report);
                FinalWeeklyReport revised = ai.withLlmByRole("simple")
                        .creating(FinalWeeklyReport.class)
                        .fromPrompt(prompt);
                return new FinalizeReportState(revised, okr, teamReports, jiraIssues, analyses);
            }
        }
    }

    /**
     * 4단계: 완료 및 결과 반환
     */
    @State
    public record FinishedState(FinalWeeklyReport finalReport, List<TeamAnalysis> analyses) implements Stage {
        @Action
        @AchievesGoal(description = "주간보고서가 최종 승인됨")
        public FinalWeeklyReport done() {
            return finalReport;
        }
    }
}
