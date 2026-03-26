package io.autocrypt.jwlee.cowork.scaffoldagent;

import com.embabel.agent.api.annotation.LlmTool;
import org.springframework.stereotype.Component;

/**
 * 전용 도구(Tools) 모음 템플릿입니다.
 * 해당 에이전트에서만 사용하는 외부 API 호출이나 도메인 로직을 정의합니다.
 */
@Component
public class ScaffoldTools {

    @LlmTool(description = "스캐폴딩 데모용 도구입니다. 특정 값을 가공하여 반환합니다.")
    public String getScaffoldData(String input) {
        return "Processed by Tool: " + input;
    }
}