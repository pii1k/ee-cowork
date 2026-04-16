# Bitbucket PR Review Agent Usage Guide

이 에이전트는 Bitbucket PR의 변경사항을 분석하여 제품 스펙 준수 여부, 표준 준수 여부, 스타일 가이드 준수 여부 등을 검사하고 자동으로 코멘트를 게시합니다.

## 주요 기능
- **RAG 기반 검토**: 제품 매뉴얼 및 표준 문서를 참조하여 코드의 정확성 검증 (표준 최우선)
- **10대 검사 기준**: 논리, 성능, 보안, 스타일, 테스트 유무 등 10개 이상의 엄격한 기준 적용
- **테스트 강제**: 신규/변경 코드에 테스트가 없는 경우 `MUST_FIX` 코멘트 강제 생성
- **대형 파일 처리**: 500줄 이상의 파일은 상단부 분석 후 안내 코멘트 게시
- **Bitbucket 연동**: 분석 결과를 PR에 전역 코멘트 및 라인 코멘트로 자동 게시

## 필수 설정 (Environment Variables)
아래 환경 변수 중 하나가 설정되어 있어야 Bitbucket API 호출이 가능합니다.
- `BITBUCKET_API_TOKEN`: Bitbucket App Password 또는 Access Token
- `ATLASSIAN_API_TOKEN`: Atlassian API Token (공용 사용 시)

## CLI 명령어 사용법
JWLEE CLI 쉘에서 `pr-review` 명령어를 사용합니다.

```bash
pr-review --repo "workspace/repository" --prId 123 --manuals "guides" --standards "standards" --styleGuide "https://auto-jira.atlassian.net/wiki/x/EwBfN"
```

### 파라미터 상세
- `--repo`: Bitbucket 저장소 슬러그 (기본값: `autocrypt/securityplatform`)
- `--prId`: (필수) Pull Request ID (숫자)
- `--manuals`: 제품 매뉴얼이 들어있는 로컬 폴더 경로 (기본값: `guides`)
- `--standards`: 표준 문서가 들어있는 로컬 폴더 경로 (기본값: `standards`)
- `--styleGuide`: 스타일 가이드 Confluence URL (기본값 제공됨)
- `--show-prompts`: 실행 시 LLM에 전달되는 프롬프트를 화면에 출력 (기본값: false)
- `--show-responses`: 실행 시 LLM의 응답 내용을 화면에 출력 (기본값: false)

## 출력 결과
1. **전역 코멘트**: PR의 'Overview' 탭에 전체 요약 및 주요 발견 사항이 게시됩니다.
2. **라인 코멘트**: 각 소스 파일의 특정 라인에 상세 분석 내용이 게시됩니다.
3. **분석 리포트**: CLI 화면에 전체 점수 및 요약 내용이 출력됩니다.
