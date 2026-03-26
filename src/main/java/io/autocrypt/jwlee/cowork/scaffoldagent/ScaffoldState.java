package io.autocrypt.jwlee.cowork.scaffoldagent;

import com.embabel.agent.api.annotation.State;

/**
 * 에이전트의 내부 워크플로우 상태(State)를 정의하는 템플릿입니다.
 * (기본 제공되는 CoreApprovalState 외에 추가적인 다단계 상태 기반 분기나 루프가 필요할 때 확장하여 사용합니다)
 */
@State
public interface ScaffoldState {
    
    // 예시: 추가 데이터 수집이 필요한 상태
    record NeedMoreInfo(String missingField) implements ScaffoldState {}
    
    // 예시: 실행 준비가 완료된 상태
    record ReadyToExecute(String verifiedData) implements ScaffoldState {}
}