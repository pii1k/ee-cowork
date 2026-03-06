package io.autocrypt.jwlee.cowork.weeklyreport.dto;

import java.util.List;

public record OkrInfo(String quarter, List<String> objectives, String title) {}
