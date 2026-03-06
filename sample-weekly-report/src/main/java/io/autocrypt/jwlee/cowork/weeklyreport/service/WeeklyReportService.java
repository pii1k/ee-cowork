package io.autocrypt.jwlee.cowork.weeklyreport.service;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import io.autocrypt.jwlee.cowork.weeklyreport.domain.WeeklyReportEntity;
import io.autocrypt.jwlee.cowork.weeklyreport.dto.*;
import io.autocrypt.jwlee.cowork.weeklyreport.repository.WeeklyReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WeeklyReportService {

    private final ConfluenceService confluenceService;
    private final JiraExcelService jiraExcelService;
    private final WeeklyReportRepository repository;
    private final AgentPlatform agentPlatform;
    
    private final ConcurrentHashMap<String, AgentProcess> activeProcesses = new ConcurrentHashMap<>();

    public WeeklyReportService(ConfluenceService confluenceService, 
                               JiraExcelService jiraExcelService, 
                               WeeklyReportRepository repository, 
                               AgentPlatform agentPlatform) {
        this.confluenceService = confluenceService;
        this.jiraExcelService = jiraExcelService;
        this.repository = repository;
        this.agentPlatform = agentPlatform;
    }

    public String startGeneration(String meetingPageId) {
        OkrInfo okr = confluenceService.getOkr();
        List<TeamReportInfo> teamReports = confluenceService.getTeamReports(meetingPageId);
        List<JiraIssueInfo> jiraIssues = jiraExcelService.readIssues();

        var invocation = AgentInvocation.create(agentPlatform, FinalWeeklyReport.class);
        
        // Wrap lists into distinct types to avoid type erasure issues in the planner
        AgentProcess process = invocation.run(
            okr, 
            new TeamReportList(teamReports), 
            new JiraIssueList(jiraIssues)
        );
        
        String processId = process.getId();
        activeProcesses.put(processId, process);

        return processId;
    }

    public void provideFeedback(String processId, boolean approved, String comments) {
        AgentProcess process = activeProcesses.get(processId);
        if (process != null && process.getStatus() == AgentProcessStatusCode.WAITING) {
            process.getBlackboard().addObject(new HumanFeedback(approved, comments));
            process.run();
        }
    }

    public AgentProcess getProcess(String processId) {
        return activeProcesses.get(processId);
    }

    @Transactional
    public WeeklyReportEntity saveFinalReport(String processId) {
        AgentProcess process = activeProcesses.get(processId);
        if (process != null && (process.getStatus() == AgentProcessStatusCode.COMPLETED)) {
            io.autocrypt.jwlee.cowork.weeklyreport.agent.WeeklyReportAgent.FinishedState finishedState = 
                process.getBlackboard().last(io.autocrypt.jwlee.cowork.weeklyreport.agent.WeeklyReportAgent.FinishedState.class);
            
            FinalWeeklyReport report = finishedState.finalReport();
            
            StringBuilder contentBuilder = new StringBuilder();
            contentBuilder.append(report.noticeHtml()).append("<hr>").append(report.requestHtml());
            
            if (finishedState.analyses() != null && !finishedState.analyses().isEmpty()) {
                contentBuilder.append("<hr><h3 class=\"mt-5 text-secondary\">[참고] 팀별 상세 분석 내역 (1단계)</h3>");
                for (TeamAnalysis analysis : finishedState.analyses()) {
                    contentBuilder.append("<div class=\"card mb-3\"><div class=\"card-header bg-light fw-bold\">")
                                  .append(analysis.teamName()).append("</div><div class=\"card-body\"><ul>");
                    
                    contentBuilder.append("<li><b>현재 OKR:</b><br/>").append(analysis.currentOkr().replace("\n", "<br/>")).append("</li>");
                    contentBuilder.append("<li><b>진행중인 이슈(회의록):</b><br/>").append(analysis.currentMeetingIssues().replace("\n", "<br/>")).append("</li>");
                    contentBuilder.append("<li><b>Jira 이슈:</b><br/>").append(analysis.currentJiraIssues().replace("\n", "<br/>")).append("</li>");
                    contentBuilder.append("<li class=\"mt-2\"><b>AI 분석 의견:</b><br/>").append(analysis.aiOpinion().replace("\n", "<br/>")).append("</li>");
                    
                    contentBuilder.append("</ul></div></div>");
                }
            }
            
            WeeklyReportEntity entity = WeeklyReportEntity.builder()
                    .title("최종 주간보고 - " + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                    .content(contentBuilder.toString())
                    .createdAt(LocalDateTime.now())
                    .build();
            
            activeProcesses.remove(processId);
            return repository.save(entity);
        }
        return null;
    }

    public WeeklyReportEntity getReportById(Long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Invalid report Id:" + id));
    }

    public List<WeeklyReportEntity> getAllReports() {
        return repository.findAllByOrderByCreatedAtDesc();
    }
}
