# ArchitectureAgent Usage Guide

`ArchitectureAgent`는 지정된 코드베이스의 디렉토리 구조, 설정 파일, 그리고 핵심 소스 코드를 탐색하여 **시스템의 구조와 모듈 간 의존성, 진입점(Entry Points), 주요 기술 스택**을 도출하는 3단계 분석 에이전트입니다.

## 1. CLI Command: `arch-analyze`

주어진 경로의 코드베이스 아키텍처를 분석합니다.

### 문법
```bash
arch-analyze [--path <directory_path>] [--context "<project_context>"] [-p] [-r]
```

### 파라미터
- `--path` (선택 사항, 기본값: `.`): 분석할 프로젝트나 디렉토리의 루트 경로입니다.
- `--context` (선택 사항, 기본값: `General Java project`): 에이전트가 어떤 관점에서 코드를 읽어야 하는지 알려주는 힌트입니다. (예: "Spring Boot MSA 프로젝트 중 하나인 결제 시스템입니다.")
- `-p` / `--show-prompts`: 에이전트가 LLM에게 보내는 프롬프트 내용을 출력합니다.
- `-r` / `--show-responses`: 에이전트가 LLM으로부터 받는 중간 응답(JSON 등)을 출력합니다.

### 실행 예시
```bash
# 현재 디렉토리를 일반적인 관점에서 분석
arch-analyze

# 특정 경로를 지정하고, AI 에이전트 프로젝트임을 힌트로 주어 분석
arch-analyze --path "src/main/java/io/autocrypt" --context "Spring Boot 기반의 멀티 에이전트 시스템입니다."

# 에이전트의 3단계 사고 과정(프롬프트와 응답)을 추적하며 분석
arch-analyze -p -r
```

## 2. 챗봇 연동 (ChatbotAgent)

CLI 모드(`chat` 명령어)에 진입한 상태에서도 `ArchitectureAgent`를 하위 에이전트(Subagent)로 호출하여 자연어로 분석을 지시할 수 있습니다.

### 대화 예시
- "현재 경로(`.` )의 코드베이스를 분석해서 아키텍처 다이어그램을 그리기 위한 리포트를 만들어줘."
- "이 프로젝트의 모듈 구조와 핵심 진입점(Entry Point)이 어디인지 자세히 설명해 줘."

## 3. 에이전트 동작 원리 (The 3-Stage Pipeline)

`ArchitectureAgent`는 단순 파일 탐색을 넘어 다음 3단계를 거칩니다.

1. **MapProjectStructure (1단계)**: `glob`과 설정 파일(`pom.xml` 등) `readFile`을 통해 프로젝트의 거시적 뼈대(Macro-structure)와 후보 모듈들을 매핑합니다.
2. **DeepDiveModules (2단계)**: `grep`과 `readFile`을 사용해 1단계에서 찾은 핵심 모듈의 **실제 자바 클래스를 열람**합니다. `import` 구문과 `@Service`, `@Controller` 어노테이션 등을 직접 확인하여 모듈 간의 진짜 의존성과 책임을 증명합니다.
3. **CompileReport (3단계)**: 1, 2단계에서 확보한 확실한 증거들만 모아, 전문적인 한국어 아키텍처 용어로 최종 보고서(`ArchitectureReport`)를 작성합니다.

> **주의**: 데모 용도인 `web/` 디렉토리는 보안 및 분석 스코프 정책에 따라 분석 대상에서 강제로 제외됩니다.
