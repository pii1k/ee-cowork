package io.autocrypt.jwlee.cowork.agents.readme;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.chat.AssistantMessage;
import io.autocrypt.jwlee.cowork.core.hitl.ApprovalDecision;
import io.autocrypt.jwlee.cowork.core.hitl.CoreApprovalState;
import io.autocrypt.jwlee.cowork.core.tools.CoreFileTools;
import org.springframework.stereotype.Component;

/**
 * README.md 파일을 업데이트하는 실제 샘플 에이전트입니다.
 */
@Agent(description = "README.md 파일 업데이트 에이전트. 요청에 따라 README.md를 읽고 수정 계획을 수립한 뒤, 승인을 거쳐 업데이트합니다.")
@Component
public class ReadmeUpdaterAgent {

    private final CoreFileTools fileTools;

    public ReadmeUpdaterAgent(CoreFileTools fileTools) {
        this.fileTools = fileTools;
    }

    public record ReadmeUpdateResult(String message) {}
    public record ProposedUpdate(String newContent) {}

    // 1. 계획(Plan) 수립 단계
    @Action
    public CoreApprovalState proposePlan(UserInput input, Ai ai) {
        String currentReadme = "";
        try {
            CoreFileTools.FileResult result = fileTools.readFile("README.md");
            if ("SUCCESS".equals(result.status())) {
                currentReadme = result.content();
            } else {
                currentReadme = "(README.md를 읽지 못했습니다: " + result.status() + ". 새로 작성합니다.)";
            }
        } catch (Exception e) {
            currentReadme = "(README.md 파일 처리 중 오류 발생. 새로 작성합니다.)";
        }
        
        String newContent = ai.withAutoLlm()
                .generateText(String.format("""
                    현재 README.md 내용:
                    %s
                    
                    사용자 요청: %s
                    
                    위 요청을 반영하여 README.md의 전체 새 내용을 작성하세요.
                    마크다운 형식의 텍스트만 출력해야 하며, JSON이나 다른 부가적인 설명은 제외하세요.
                    """, currentReadme, input.getContent()));
        
        return new CoreApprovalState(
            "README.md 업데이트 승인 요청",
            newContent
        );
    }

    // 2. 실행 단계
    @Action
    @AchievesGoal(description = "README.md가 승인되고 성공적으로 업데이트되었습니다.")
    public ReadmeUpdateResult executePlan(CoreApprovalState state, ApprovalDecision decision, ActionContext ctx) throws Exception {
        if (decision.approved()) {
            fileTools.writeFile("README.md", state.planDescription());
            ctx.sendMessage(new AssistantMessage("README.md 파일이 성공적으로 업데이트되었습니다."));
            return new ReadmeUpdateResult("Success");
        } else {
            ctx.sendMessage(new AssistantMessage("업데이트가 거절되었습니다. 사유: " + decision.comment()));
            return new ReadmeUpdateResult("Rejected");
        }
    }
}
