package io.autocrypt.jwlee.cowork.weeklyreport.controller;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import io.autocrypt.jwlee.cowork.weeklyreport.domain.WeeklyReportEntity;
import io.autocrypt.jwlee.cowork.weeklyreport.service.ConfluenceService;
import io.autocrypt.jwlee.cowork.weeklyreport.service.WeeklyReportService;
import io.autocrypt.jwlee.cowork.weeklyreport.dto.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
public class WeeklyReportController {

    private final WeeklyReportService weeklyReportService;
    private final ConfluenceService confluenceService;

    public WeeklyReportController(WeeklyReportService weeklyReportService, ConfluenceService confluenceService) {
        this.weeklyReportService = weeklyReportService;
        this.confluenceService = confluenceService;
    }

    @GetMapping("/")
    public String list(Model model) {
        model.addAttribute("reports", weeklyReportService.getAllReports());
        return "list";
    }

    @GetMapping("/generate")
    public String generateForm(Model model) {
        model.addAttribute("meetingUrls", confluenceService.getRecentMeetingUrls());
        model.addAttribute("okr", confluenceService.getOkr());
        return "generate";
    }

    @PostMapping("/generate")
    public String startGeneration(@RequestParam String meetingUrl, Model model) {
        String processId = weeklyReportService.startGeneration(meetingUrl);
        model.addAttribute("processId", processId);
        return "fragments/generation-status :: status";
    }

    @GetMapping("/status/{processId}")
    public String checkStatus(@PathVariable String processId, Model model) {
        AgentProcess process = weeklyReportService.getProcess(processId);
        if (process == null) return "fragments/error :: error";

        model.addAttribute("processId", processId);
        model.addAttribute("statusCode", process.getStatus());

        if (process.getStatus() == AgentProcessStatusCode.WAITING) {
            // 현재 머물고 있는 상태(State) 객체 추출
            io.autocrypt.jwlee.cowork.weeklyreport.agent.WeeklyReportAgent.AnalyzeTeamsState analyzeState = process.getBlackboard().last(io.autocrypt.jwlee.cowork.weeklyreport.agent.WeeklyReportAgent.AnalyzeTeamsState.class);
            io.autocrypt.jwlee.cowork.weeklyreport.agent.WeeklyReportAgent.FinalizeReportState finalizeState = process.getBlackboard().last(io.autocrypt.jwlee.cowork.weeklyreport.agent.WeeklyReportAgent.FinalizeReportState.class);
            
            if (finalizeState != null) {
                // 2단계: 최종 보고서 검토 상태
                model.addAttribute("finalReport", finalizeState.report());
            } else if (analyzeState != null) {
                // 1단계: 팀별 분석 검토 상태
                model.addAttribute("analyses", analyzeState.analyses());
            }

            return "fragments/approval-form :: form";
        }

        if (process.getStatus() == AgentProcessStatusCode.COMPLETED) {
            FinalWeeklyReport finalReport = process.getBlackboard().last(FinalWeeklyReport.class);
            model.addAttribute("finalReport", finalReport);
            return "fragments/finalize-complete :: complete";
        }

        return "fragments/generation-status :: loading";
    }

    @PostMapping("/feedback/{processId}")
    public String provideFeedback(@PathVariable String processId, 
                                  @RequestParam boolean approved, 
                                  @RequestParam(required = false) String comments,
                                  Model model) {
        weeklyReportService.provideFeedback(processId, approved, comments);
        model.addAttribute("processId", processId);
        return "fragments/generation-status :: loading";
    }

    @PostMapping("/finalize/{processId}")
    public String finalizeAndSave(@PathVariable String processId, Model model) {
        WeeklyReportEntity report = weeklyReportService.saveFinalReport(processId);
        return "redirect:/reports/" + report.getId();
    }

    @GetMapping("/reports/{id}")
    public String viewReport(@PathVariable Long id, Model model) {
        WeeklyReportEntity report = weeklyReportService.getReportById(id);
        model.addAttribute("report", report);
        return "detail";
    }

    @GetMapping("/reports/{id}/download")
    public org.springframework.http.ResponseEntity<byte[]> downloadReport(@PathVariable Long id) {
        WeeklyReportEntity report = weeklyReportService.getReportById(id);
        
        String htmlContent = "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n<title>" + report.getTitle() + "</title>\n</head>\n<body>\n" 
                + "<h2>" + report.getTitle() + "</h2>\n"
                + report.getContent() 
                + "\n</body>\n</html>";
                
        byte[] content = htmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.TEXT_HTML);
        headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment().filename("weekly_report_" + id + ".html").build());
        
        return new org.springframework.http.ResponseEntity<>(content, headers, org.springframework.http.HttpStatus.OK);
    }
}
