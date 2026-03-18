package io.autocrypt.jwlee.cowork.agents.weekly.service;

import io.autocrypt.jwlee.cowork.agents.weekly.dto.MeetingInfo;
import io.autocrypt.jwlee.cowork.agents.weekly.dto.OkrInfo;
import io.autocrypt.jwlee.cowork.agents.weekly.dto.TeamReportInfo;
import java.util.List;

public interface ConfluenceService {
    OkrInfo getOkr();
    List<TeamReportInfo> getTeamReports(String meetingUrl);
    List<MeetingInfo> getRecentMeetingUrls();
    String getPageStorage(String pageId);
}
