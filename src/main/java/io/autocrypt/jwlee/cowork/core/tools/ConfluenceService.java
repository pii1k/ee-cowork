package io.autocrypt.jwlee.cowork.core.tools;

import io.autocrypt.jwlee.cowork.core.dto.MeetingInfo;
import io.autocrypt.jwlee.cowork.core.dto.OkrInfo;
import io.autocrypt.jwlee.cowork.core.dto.TeamReportInfo;
import java.util.List;

public interface ConfluenceService {
    OkrInfo getOkr();
    List<TeamReportInfo> getTeamReports(String meetingUrl);
    List<MeetingInfo> getRecentMeetingUrls();
    String getPageStorage(String pageId);
}
