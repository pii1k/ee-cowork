package io.autocrypt.jwlee.cowork.scaffoldagent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.chat.AssistantMessage;
import io.autocrypt.jwlee.cowork.core.hitl.ApprovalDecision;
import io.autocrypt.jwlee.cowork.core.hitl.CoreApprovalState;
import org.springframework.stereotype.Component;

/**
 * Vibe Coding으로 새로운 에이전트를 만들 때 복사하여 시작하는 템플릿입니다.
 */
@Agent(description = "스캐폴딩 데모 에이전트. 새로운 기능을 개발할 때 이 템플릿을 기반으로 시작하세요.")
@Component
public class ScaffoldAgent {

    private final ScaffoldTools tools;

    public ScaffoldAgent(ScaffoldTools tools) {
        this.tools = tools;
    }

    public record ScaffoldResult(String finalOutput) {}

    // 1. 계획(Plan) 수립 단계
    // 사용자의 입력을 받아 작업을 계획하고, 승인을 받기 위한 CoreApprovalState를 반환합니다.
    @Action
    public CoreApprovalState proposePlan(UserInput input, Ai ai) {
        // 도구 사용 예시
        String data = tools.getScaffoldData(input.getContent());
        
        return new CoreApprovalState(
            "User requested: " + input.getContent(),
            "Plan: " + data
        );
    }

    // 2. 실행 단계
    // 사용자가 승인(ApprovalDecision)하면 이 액션이 실행되어 목표(Goal)를 달성합니다.
    @Action
    @AchievesGoal(description = "계획이 승인되고 성공적으로 실행되었습니다.")
    public ScaffoldResult executePlan(CoreApprovalState state, ApprovalDecision decision, ActionContext ctx) {
        if (decision.approved()) {
            ctx.sendMessage(new AssistantMessage("Plan executed successfully! Output based on plan: " + state.planDescription()));
            return new ScaffoldResult("Success: " + state.planDescription());
        } else {
            ctx.sendMessage(new AssistantMessage("Plan rejected. Reason: " + decision.comment()));
            return new ScaffoldResult("Rejected");
        }
    }
}