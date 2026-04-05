# Embabel Agent DSL: DocDiffAgent

이 문서는 Embabel Agent Framework의 두 버전(0.3.4 vs 4.0.0) 간의 가이드 문서 차이를 분석하는 `DocDiffAgent`의 명세입니다.

## 1. Metadata
```yaml
agent:
  name: DocDiffAgent
  description: "두 버전의 기술 문서를 비교하여 추가/삭제/변경 사항을 구조적으로 분석하고 한국어 보고서를 생성하는 에이전트"
  timezone: "Asia/Seoul"
  language: "Korean"
  workspace: "doc-diff"
```

## 2. Dependencies
- `CoreFileTools`: 문서 헤더 추출(grep) 및 섹션별 본문 읽기
- `PromptProvider`: Jinja 템플릿 로딩

## 3. Domain Objects (DTOs)

```yaml
DocVersion:
  version: String
  filePath: String

TOCEntry:
  title: String
  level: int
  startLine: int
  endLine: int # 다음 헤더 전까지의 범위

TOCMapResult:
  sourceVersion: String
  targetVersion: String
  added: List<TOCEntry>
  removed: List<TOCEntry>
  modified: List<MappedSection> # 이름이 비슷하거나 위치가 바뀐 섹션 쌍

MappedSection:
  source: TOCEntry
  target: TOCEntry
  similarityReason: String # LLM이 판단한 매핑 근거

SectionDiff:
  title: String
  changeType: String # ADDED, REMOVED, MODIFIED
  technicalSummary: String # 기술적 변경 사항 요약
  impact: String # 개발자에게 미치는 영향
  isBreaking: boolean

DocDiffReport:
  content: String # 최종 Markdown 보고서 (한국어)
```

## 4. Actions

### Action: extractTOC
- **Description**: Java 로직과 `grep`을 사용하여 문서의 모든 헤더를 추출하고 섹션별 라인 범위를 계산합니다.
- **Input**: `DocVersion`
- **Output**: `List<TOCEntry>`
- **Logic**: 
  - `^#+` (Markdown) 또는 `^=+` (AsciiDoc/HTML-like) 패턴을 찾아 제목과 라인 번호 추출.
  - 리스트를 순회하며 각 섹션의 `endLine`을 다음 섹션의 `startLine - 1`로 설정.

### Action: mapTOCs
- **Description**: 두 버전의 TOC 리스트를 비교하여 추가, 삭제, 변경(Fuzzy match) 섹션을 분류합니다.
- **Input**: `List<TOCEntry>` (source), `List<TOCEntry>` (target)
- **Output**: `TOCMapResult`
- **LLM Configuration**:
  - `role`: normal
  - `template`: `agents/docdiff/map-toc.jinja`
- **Fuzzy Logic**: 섹션 번호가 바뀌었거나 제목이 약간 수정된 경우(예: "1.1. Intro" -> "1.1 Introduction")를 LLM이 매핑합니다.

### Action: analyzeSectionContent
- **Description**: 매핑된 섹션의 실제 본문 내용을 비교하여 구체적인 기능 변화를 분석합니다.
- **Input**: `MappedSection`
- **Output**: `SectionDiff`
- **LLM Configuration**:
  - `role`: performant
  - `template`: `agents/docdiff/compare-content.jinja`
- **Strategy**: 두 섹션의 라인 범위를 읽어 LLM에게 "바뀐 속성, 새로운 어노테이션, 동작 변경점"을 찾으라고 지시합니다.

### Action: synthesizeFinalReport
- **Goal**: `@AchievesGoal(description = "기술 문서 버전 차이 분석 보고서 생성")`
- **Input**: `TOCMapResult`, `List<SectionDiff>`
- **Output**: `DocDiffReport`
- **LLM Configuration**:
  - `role`: performant
  - `template`: `agents/docdiff/synthesize-report.jinja`
- **Report Format**: 
  1. **없어진 기능**: `TOCMapResult.removed` 기반
  2. **추가된 기능**: `TOCMapResult.added` 및 신규 섹션 분석 기반
  3. **달라진 부분**: `List<SectionDiff>` 중 중요도가 높은 항목 위주 요약

## 5. Implementation Guidelines

### 5.1 Architecture
- 패키지: `io.autocrypt.jwlee.cowork.docdiffagent`
- CLI 명령: `BaseAgentCommand` 상속, `doc-diff --source 0.3.4.md --target 4.0.0.md` 형태 지원.

### 5.2 Pitfall Protection (Mandatory)
- **Memory Management**: 분석해야 할 섹션이 많을 경우 루프를 돌며 개별적으로 `analyzeSectionContent`를 호출하십시오. 한꺼번에 모든 본문을 읽으면 Context Overflow가 발생합니다.
- **DICE Implementation**: `TOCEntry` 레코드 내부에 `readContent(CoreFileTools tools)`와 같은 `@LlmTool`을 정의하여 LLM이 필요한 시점에 본문을 직접 읽어갈 수 있게 구성하는 것을 권장합니다.
- **Language**: 모든 보고서 출력물은 한국어로 작성하되, 기술 용어(DTO, RAG, Action 등)는 가급적 원문을 유지하십시오.

### 5.3 Extension
- 본 에이전트는 마크다운 헤더 형식을 따르는 모든 문서에 범용적으로 적용될 수 있도록 정규식 기반 헤더 추출기를 사용해야 합니다.
