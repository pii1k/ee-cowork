package io.autocrypt.jwlee.cowork.weeklyagent.dto;

import io.autocrypt.jwlee.cowork.core.dto.TeamReportInfo;
import java.util.List;

public record TeamReportList(List<TeamReportInfo> reports) {}
