# Embabel Agent DSL: BitbucketPrReviewAgent (v1.0)
<!--
이 문서는 Bitbucket PR 리뷰를 자동화하는 Embabel 에이전트의 명세서입니다.
제품 매뉴얼, 표준 문서, 스타일 가이드를 RAG로 참조하여 논리적 결함 및 스펙 준수 여부를 검사합니다.
참조: guides/DSL_GUIDE.md
-->

## 1. Metadata
```yaml
agent:
  name: BitbucketPrReviewAgent
  description: "Bitbucket PR의 변경사항을 분석하여 논리, 제품 스펙, 스타일 가이드, 테스트 충실도를 검사하는 에이전트"
  timezone: "Asia/Seoul"
  language: "Korean"
  workspace: "bitbucket-pr-review"
```

## 2. Dependencies
- `CoreFileTools`: 로컬 파일 시스템의 매뉴얼 및 표준 문서 읽기
- `GitTools`: Bitbucket PR Diff 추출 및 분석 (Diff Parser 포함)
- `LocalRagTools`: 제품 매뉴얼 및 표준 문서용 Lucene RAG 인스턴스 관리
- `PromptProvider`: Jinja2 템플릿 (`.jinja`) 로딩
- `CoreWorkspaceProvider`: 분석 결과 및 임시 파일 저장 경로 제공
- `ApplicationContext`: 이벤트 발행 (Notification)

## 3. Domain Objects (DTOs)
```yaml
# PR 분석 요청 정보
PrReviewRequest:
  repositorySlug: String # @NotBlank
  pullRequestId: Long # @Min(1)
  manualsDir: String # 개발자 제품 매뉴얼 폴더 경로
  standardsDir: String # 구현 대상 표준 문서 폴더 경로
  styleGuideUrl: String # Golden Rule 스타일 가이드 (https://auto-jira.atlassian.net/wiki/x/EwBfN)

# 코드 리뷰 코멘트 객체
CodeComment:
  fileName: String
  lineNumber: Integer # Nullable (전역 코멘트의 경우 null)
  content: String # Markdown 형식의 리뷰 내용
  type: String # "GLOBAL" | "LINE"
  severity: String # "MUST_FIX" | "SHOULD_FIX" | "SUGGESTION"
  criteriaId: String # 10개 기준 중 어떤 것에 해당하는지

# 개별 파일/세그먼트 분석 결과
AnalysisResult:
  comments: List<CodeComment>
  score: Integer # 0-100 (분석된 세그먼트의 품질 점수)
  isTruncated: boolean # 파일이 너무 길어 일부만 분석되었는지 여부

# 최종 수합 리포트
FinalReviewReport:
  overallScore: Integer # 전체 정량적 점수
  summary: String # 전체 요약 (전역 코멘트 포함)
  globalComments: List<CodeComment>
  lineComments: List<CodeComment> # 파일별, 라인별로 정렬된 코멘트
  totalIssuesFound: int
  truncatedFiles: List<String> # 분석 한계를 넘어선 파일 목록
```

## 4. Workflow States
```yaml
State: InitialState:
  request: PrReviewRequest

State: ContextReadyState:
  request: PrReviewRequest
  diffSegments: List<DiffSegment> # GitTools로 추출된 파일별 Diff
  manualsRagKey: String # 매뉴얼 RAG 인스턴스 식별자
  standardsRagKey: String # 표준 문서 RAG 인스턴스 식별자

State: AnalysisState:
  results: List<AnalysisResult>
  processedCount: int
```

## 5. Actions

### Action: PrepareReviewContext
- **Goal**: RAG 인스턴스 초기화 및 PR Diff 데이터 로드
- **Input**: `InitialState`
- **Output**: `ContextReadyState`
- **Logic**:
  - `LocalRagTools`를 사용하여 `manualsDir`과 `standardsDir`을 인덱싱(최초 1회)하거나 기존 인덱스를 오픈합니다.
  - `GitTools`를 통해 해당 PR의 Diff를 가져와 파일별로 분리합니다.
  - 파일당 최대 라인 수(기본 500라인)를 초과하는 경우 절단(Truncate) 처리를 수행하고 `isTruncated` 플래그를 세팅합니다.

### Action: AnalyzeCodeSegment
- **Goal**: 개별 Diff 세그먼트에 대해 10개 이상의 엄격한 기준을 바탕으로 심층 분석 수행
- **Input**: `ContextReadyState`
- **Output**: `AnalysisResult`
- **LLM Configuration**:
  - `role`: performant (Gemini 2.5 Pro 등 고성능 모델)
  - `temperature`: 0.1 (일관성 있는 정량적 평가를 위해 낮게 설정)
  - `template`: `agents/bitbucketprapp/analyze-code.jinja`
- **Prompt Requirements (The 10+ Criteria)**:
  1. **논리적 무결성**: 알고리즘 오류, Edge Case 처리 누락.
  2. **표준 준수 (Highest Priority)**: RAG로 수집된 표준 문서 규격 일치 여부.
  3. **제품 스펙 준수**: 매뉴얼에 명시된 기능 정의와의 일치성.
  4. **스타일 가이드 준수**: 지정된 Confluence Golden Rule 적용 여부.
  5. **테스트 존재 여부 (MUST)**: 신규/변경 코드에 대한 테스트 코드 포함 여부.
  6. **테스트 유의미성**: Assert가 적절한지, 주요 로직을 커버하는지 검사.
  7. **자원 및 성능**: 메모리 누수, 불필요한 루프, DB 쿼리 비효율.
  8. **보안**: 하드코딩된 비밀번호, SQL 인젝션, 권한 검사 누락.
  9. **가독성 및 명명법**: 변수/함수 이름의 직관성, 함수 길이.
  10. **예외 처리**: 적절한 예외 타입 사용 및 로그 기록 여부.
  - *각 항목별로 Good/Bad 예시와 0~10점 사이의 배점 기준을 프롬프트에 명시할 것.*

### Action: SynthesizeFinalReport
- **AchievesGoal**: 모든 분석 결과를 누락 없이 수합하고 최적화하여 최종 리포트 생성
- **Input**: `AnalysisState`
- **Output**: `FinalReviewReport`
- **Logic**:
  - 중복된 코멘트(여러 파일에서 공통적으로 발생하는 스타일 오류 등)를 병합하여 전역 코멘트로 승격하거나 정리합니다.
  - `isTruncated`인 파일의 경우, 첫 번째 라인 코멘트로 "이 파일은 500줄을 초과하여 상단부만 분석되었습니다."라는 경고를 강제 삽입합니다.
  - 개별 점수를 가중 평균하여 전체 점수를 산출합니다.

## 6. Implementation Guidelines
- **Modulith Package**: `io.autocrypt.jwlee.cowork.bitbucketprapp` 패키지 사용.
- **RAG Strategy**: `localRagTools.getOrOpenInstance`를 사용하여 영속성을 유지합니다.
- **Style Guide Injection**: Confluence URL의 내용은 실제 구현 시 `PromptProvider`를 통해 텍스트 전문이 프롬프트의 `<style_guide>` 태그 내에 주입되도록 처리합니다.
- **Test Enforcement**: 테스트 코드가 없는 경우 `severity: MUST_FIX`인 코멘트가 반드시 생성되도록 로직을 강제합니다.
- **Concurrency**: `AnalyzeCodeSegment`는 병렬로 실행 가능하도록 설계하며, Embabel의 GOAP 플래너가 이를 최적화하도록 지원합니다.
