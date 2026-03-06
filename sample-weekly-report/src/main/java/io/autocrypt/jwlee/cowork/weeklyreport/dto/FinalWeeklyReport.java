package io.autocrypt.jwlee.cowork.weeklyreport.dto;

import java.util.List;

/**
 * 최종 주간보고서 구조
 */
public record FinalWeeklyReport(
    String noticeHtml,      // 공지/공유사항
    String requestHtml      // 요청/대기사항
) {}
