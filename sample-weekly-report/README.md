# Sample Weekly Report (주간보고 자동 생성 시스템)

AI Agent(Embabel Framework)를 활용하여 Confluence의 주간 회의록 및 OKR 자료, 그리고 Jira 이슈 데이터를 종합 분석해 주간보고 초안을 자동 생성하는 Spring Boot 기반 웹 애플리케이션 PoC입니다.

## 📌 개요

- **핵심 목표**: 매주 반복되는 주간보고 작성을 위해 분기 OKR, 주간 팀장 회의록, Jira 이슈 트래킹 내역을 AI가 교차 분석하여 인사이트를 도출하고 HTML 형태의 주간보고서 초안을 작성합니다.

## ✨ 주요 기능 및 AI Agent Workflow

본 시스템은 **Embabel Framework** 기반의 State-Machine Agent(`WeeklyReportAgent`)로 구현되어 있습니다.

1. **데이터 수집 및 팀별 분석 (AnalyzeTeamsState)**
   - **Confluence 연동**: 이번 분기 OKR 자료와 지난주 주간 팀장 회의록을 REST API로 가져와 JSoup으로 파싱합니다.
   - **Jira 연동**: 최근 4주간 업데이트된 Jira 이슈 데이터를 JQL을 통해 수집합니다.
   - **AI 분석**: OKR, 주간회의, Jira 이슈를 분석합니다.
2. **Human-in-the-Loop (사용자 검토)**
   - AI가 초안을 작성하기 전, 분석된 팀별 리포트 데이터에 대해 사용자가 피드백을 주거나 승인할 수 있는 단계를 거칩니다.
3. **최종 보고서 생성 (FinalizeReportState)**
   - 승인된 분석 결과를 바탕으로 최종 주간보고서를 HTML 포맷으로 포매팅 및 생성합니다.
4. **완료 및 다운로드 (FinishedState)**
   - 생성된 주간보고서는 Local DB(H2)에 저장되며, 언제든 조회하고 HTML로 다운로드할 수 있습니다.

## 🛠 기술 스택

- **Backend**: Java 21, Spring Boot 3.x, Spring Data JPA, Spring Security
- **Database**: H2 Database (로컬 파일 기반 작동으로 운영 편의성 증대)
- **Frontend**: Thymeleaf (SSR-first), HTMX (비동기 인터랙션), Alpine.js (제한적인 클라이언트 사이드 로직)
- **AI Framework**: Embabel (Agentic Framework)
- **LLM**: Google Gemini (`gemini-2.5-flash-lite` 등)
  - `simple`, `normal`, `performant` 3가지 Role-based 프롬프팅 설정을 지원하여 작업 복잡도에 따라 유연하게 모델 조정 가능

## 💡 원본 PLAN 대비 주요 변경/개선 사항

- **Jira 데이터 연동 방식 변경**: 초안(PLAN.md)에서는 엑셀 파일(`CAM연구소_지표.xlsx`)을 읽어들이는 방식이었으나, 실제 구현에서는 최신 데이터의 실시간성과 연동 편의성을 고려하여 **Jira REST API (JQL)**를 직접 호출하는 방식으로 개선되었습니다. (단, 클래스명은 `JiraExcelService`로 유지됨)
- **LLM 모델 티어링 구조 도입**: 단순 데이터 추출, 의견 생성, 최종 HTML 포매팅 등 난이도에 따라 AI 설정을 분리하여, 추후 비용과 성능을 최적화할 수 있는 기반을 마련했습니다. (초기 설정은 모두 `gemini-2.5-flash-lite`로 연결)

## 🚀 시작하기

### 환경 변수 설정
앱을 실행하기 전, 다음 환경변수 및 인증 정보 설정이 필요합니다. (Confluence, Jira API Token 및 Google Gemini API Key)
- `GEMINI_API_KEY`: Google Gemini API 키

### 실행 방법
```bash
# Maven을 이용한 Spring Boot 앱 실행
../mvnw spring-boot:run
```

- **접속 주소**: `http://localhost:8081`
- **로그인**: Spring Security가 적용되어 있습니다. 애플리케이션에 설정된 고정 ID/PW를 사용하여 로그인합니다.

## 📂 주요 디렉토리 구조

- `src/main/java/.../agent/`: Embabel 기반 주간보고 생성 AI Agent 로직 및 State 정의
- `src/main/java/.../service/`: Confluence, Jira 등 외부 시스템 연동 및 비즈니스 로직
- `src/main/java/.../dto/`, `domain/`: 데이터 전송 객체 및 JPA 엔티티 (H2 DB 매핑)
- `src/main/resources/templates/`: Thymeleaf + HTMX 기반의 프론트엔드 화면 (조회, 생성, 승인 폼 등)
- `src/main/resources/prompts/`: AI 분석 및 생성을 위한 프롬프트 템플릿 (Jinja 포맷)
