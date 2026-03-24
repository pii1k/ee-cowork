# Obsidian Daily Note Generation Task

You are an expert Obsidian vault manager. Your task is to generate a new Daily Note based on the provided context.

# PREVIOUS UNFINISHED TASKS
{{unfinishedTasks}}

# EXTERNAL INTEGRATIONS
## Google Tasks (GTD)
{{googleTasks}}

# DATE CONTEXT
- Today: {{today}}
- Yesterday: {{yesterday}}
- This Week: {{thisWeek}}
- Previous Week: {{previousWeek}}

# TEMPLATE STRUCTURE
up:: [[{{previousWeek}}]], [[{{yesterday}}]]

> [!Warning] AI가 작성한 초안입니다. 검토 후 이 callout은 삭제해주세요.

> [!Todo] 일일 업무 정리 (==정리 후 템플릿 지우기==)
> - **📅직전 데일리 노트**, [[🚀 월간 계획]], [[전체 할 일|🤔 업무 창고]] 확인 ➡ **오늘 할 일**에 기재
> - [구글 태스크](https://tasks.google.com/embed/?origin=https://calendar.google.com&fullWidth=1)  정리 ➡ 업무: **오늘 할 일**에 기재
> - [[+Inbox]] 정리, [Youtube 구독 영상](https://www.youtube.com/feed/subscriptions) 정리(Watch Later로 이동)
> - 아웃룩 일정 확인(수동) ➡ **오늘 일정**에 기재 (※ 현재 자동 연동은 중단된 상태입니다)
>
> ```button
> name 템플릿 지우기
> color yellow
> remove [daily-setup]
> ```
^button-daily-setup

> [!Attention] [[교정하고 싶은 습관들|습관화가 필요한 일들]]
> - 💍 반지 만지지 말기
>
> ```button
> name 템플릿 지우기
> color yellow
> remove [daily-attitude]
> ```
^button-daily-attitude

# 🎯 오늘 업무 관리
## 🏃 할 일
{{tasksToInclude}}

![[🗒 백로그]]

## 📆 일정
- [ ] 07:00 💊 출근후 약먹기
- 11:00 - 12:00 🍱 점심: *도시락 / 다이어트*

---

# 📓 노트

# INSTRUCTIONS
1. Adhere strictly to the template structure.
2. Include all unfinished tasks from the previous note in the "## 🏃 할 일" section.
3. Use the correct relative links for `up::` based on the provided date context.
4. Ensure the output is valid Markdown.
5. The warning callout MUST be at the top.
