# Custom Agentic Shell Development Plan

## 1. 프로젝트 목표
Gemini CLI와 유사한 기능을 제공하며, 개발자가 임의로 에이전트(기능)를 추가할 수 있는 **개인용 Custom Agentic Shell**을 구축한다. 

## 2. 주요 아키텍처 및 기술 스택
- **CLI 프레임워크**: Spring Shell v3.4.0
- **AI 에이전트 엔진**: Embabel (GOAP, State 기반 워크플로우, Toolish RAG)
- **모듈화 검증**: Spring Modulith (에이전트 간 의존성 차단 및 수직적 슬라이스 강제)

## 3. 핵심 요구사항 및 구현 전략

### 3.1. Human-in-the-Loop (승인/거절 시스템)
작업 수행 전 또는 파일 수정, 명령어 실행 전에 사용자에게 승인을 받는 프로세스를 구현한다.
- **구현 방식**: Embabel의 `@State` 워크플로우와 `WaitFor.formSubmission()`을 활용.
- **흐름**: Agent가 계획(Plan)을 수립 -> `ApprovalRequest` 상태로 전환하며 `WaitFor` 호출 -> Spring Shell UI(ComponentFlow 또는 ConfirmationInput)를 통해 Y/N 입력 -> AgentProcess에 결과 주입(Inject) 후 재개.

### 3.2. 제공할 도구 (Tools) 목록 선정
Gemini CLI에서 제공하는 주요 도구들을 Embabel의 `@LlmTool`로 구현하여 에이전트에게 제공한다. 이 도구들은 모든 vertical slice가 쓸 수 있는 경로에 두는, core tool 이 된다.

1. **ReadFile**: 지정된 파일의 내용을 읽어오는 도구
2. **WriteFile**: 지정된 경로에 새로운 파일을 생성하거나 덮어쓰는 도구
3. **Replace**: 파일 내의 특정 문자열을 찾아 정확히 치환하는 도구 (가장 많이 쓰임)
4. **RunShellCommand**: 터미널에서 Bash 명령어를 실행하고 결과를 반환하는 도구
5. **GrepSearch**: 정규식을 이용해 프로젝트 내 파일 내용을 검색하는 도구
6. **Glob**: 패턴(예: `src/**/*.java`)을 이용해 파일 경로를 찾는 도구
7. **ListDirectory**: 특정 디렉토리의 파일/폴더 목록을 조회하는 도구

### 3.3. Spring Modulith를 통한 Vertical Slice 아키텍처
새로운 에이전트를 추가할 때(Vibe Coding 시), 기존 코드와의 얽힘 없이 독립적으로 기능할 수 있도록 모듈화를 강제한다.
- **패키지 구조**: `io.autocrypt.jwlee.cowork.agents.<agent_name>` 하위에 해당 에이전트와 관련된 Shell Command, Agent 클래스, Tools, States 등을 모두 몰아넣는다.
- **Modulith 검증**: `spring-modulith-starter-test`를 적용하여 패키지 간 순환 참조나 허가되지 않은 의존성이 발생하면 빌드가 실패하도록 구성한다.

### 3.4. 에이전트 스캐폴딩 (Boilerplate)
Vibe Coding을 통해 손쉽게 새 에이전트를 만들 수 있도록 표준 템플릿(Boilerplate)을 설계한다. 
템플릿 패키지 (`agents.scaffold`)는 다음 요소들을 포함한다.
1. `XXXCommand.java`: Spring Shell 진입점 (명령어 정의 및 AgentProcess 시작)
2. `XXXAgent.java`: Embabel `@Agent` 메인 클래스 (행동 정의)
3. `XXXState.java`: 작업 계획 -> 승인 대기 -> 실행으로 이어지는 상태 인터페이스
4. `XXXTools.java`: 해당 에이전트 전용 `@LlmTool` 모음

## 4. 단계별 진행 계획 (Milestones)

- **Phase 1: 기반 설정 및 도구 구현**
  - Spring Modulith 의존성 추가 및 아키텍처 검증 테스트 작성
  - 7가지 공통 도구(`@LlmTool`) 구현 (Core 모듈)
- **Phase 2: HITL 워크플로우 기반 뼈대 완성**
  - Spring Shell UI와 Embabel `WaitFor` 간의 브릿지 구현 (명령어 입력 -> AI 고민 -> 승인 프롬프트 렌더링 -> AI 실행)
- **Phase 3: 스캐폴딩 패키지 작성**
  - 복사-붙여넣기(또는 자동 생성)로 즉시 쓸 수 있는 `agents.scaffold` 패키지 구성
- **Phase 4: 첫 번째 샘플 에이전트 구현**
  - 스캐폴딩을 이용해 실제로 동작하는 커스텀 에이전트 1개(예: ReadmeUpdater 등) 구현 및 테스트
- **Phase 5: 이 프로젝트에 대한 README_new.md 파일 생성**

