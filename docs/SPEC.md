# SPEC.md: code-plugin

## 0. Назначение

Этот документ — LLM-ready спецификация проекта `CODE Methodology`, IntelliJ Platform plugin для сопровождения цикла CODE / PDCA внутри IDE.

- Аудитория: coding agent, maintainer, новый разработчик плагина, reviewer.
- Как использовать: сначала прочитать `CODE.md` для operational workflow, затем этот файл для product/system behavior, capabilities и implementation plan.
- Sources of truth: `CODE.md`, `CODE.yaml`, `build.gradle.kts`, `gradle.properties`, `src/main/resources/META-INF/plugin.xml`, `src/main/kotlin/ru/codeplugin/**`.
- Assumption: проектная спека хранится в `docs/SPEC.md`, потому что в репозитории уже есть `docs/`.
- Evidence rule: ключевые утверждения ниже привязаны к `Source` или `Inferred from`.

## 1. Executive Summary

- Проект — Kotlin/JVM plugin для IntelliJ IDEA / Android Studio, который встраивает CODE Methodology в IDE workflow. Source: `src/main/resources/META-INF/plugin.xml`, `build.gradle.kts`.
- CODE Methodology адаптирует PDCA к разработке: `Prepare`, `Develop`, `Control`, `Apply`. Source: `plugin.xml`, `CODE.md`.
- Plugin регистрирует toolbar group `CODE`, startup activity, Tool Window, local inspection и actions для этапов CODE. Source: `plugin.xml`.
- Конфигурация процесса читается из `CODE.yaml`; при отсутствии или ошибке чтения используются defaults. Source: `CodeConfigService.kt`, `CodeStartupActivity.kt`.
- AI-функции используют GigaChat-compatible OAuth + chat completions и показывают ответы в Tool Window. Source: `AiAssistantService.kt`, `CodeToolWindow.kt`.
- В проекте нет `src/test/` в текущей file map; automated verification пока не зафиксирована как реализованная test suite. Source: `rg --files`.

Что важно не сломать:

- Registration contract в `plugin.xml`: action ids, notification group `CODE`, Tool Window id `CODE`, inspection `CodeNotFormatted`.
- Совместимость с IntelliJ Platform `IC 2025.1.4.1`, Java/Kotlin target 21. Source: `build.gradle.kts`, `gradle.properties`.
- Безопасность AI/config: не логировать реальные tokens, OAuth response body, credential values. Risk source: `AiAssistantService.kt`, `CODE.yaml`.

## 2. Users, Scenarios And Capabilities

| User / Agent | Scenario | Capability | Current Status | Acceptance Criteria | Source |
| --- | --- | --- | --- | --- | --- |
| IDE user | Открывает проект с `CODE.yaml` | Startup reload config and notify user | implemented | При наличии `CODE.yaml` config reload выполняется, notification сообщает загрузку; при отсутствии файла notification просит создать `CODE.yaml` | `CodeStartupActivity.kt`, `CodeConfigService.kt` |
| IDE user | Проверяет начало работы по ветке | Prepare branch name validation | implemented | Action читает `prepare.branch_format`, сопоставляет current branch, показывает OK/WARNING/ERROR notification, фиксирует branch seen | `ValidateBranchNameAction.kt`, `CodeBranchLifecycleService.kt`, `CODE.yaml` |
| IDE user | Форматирует текущий файл | Develop code style action | implemented | При `develop.require_code_style_check=true` action форматирует текущий PSI file через `CodeStyleManager`; без файла показывает warning dialog | `RunDevelopChecksAction.kt`, `CODE.yaml` |
| IDE user | Видит inspection для неформатированного файла | Local inspection for CODE formatting | partial | Inspection зарегистрирована; description сейчас требует проектной проверки на специфичность текста | `plugin.xml`, `NotFormattedInspection.kt`, `inspectionDescriptions/CodeNotFormatted.html` |
| IDE user | Проверяет coverage | Control JaCoCo coverage check | implemented | Action читает XML report path из `CODE.yaml`, парсит INSTRUCTION/LINE counters, сравнивает с threshold, показывает notification | `ControlCheckCoverageAction.kt`, `JacocoCoverageReader.kt`, `CODE.yaml` |
| IDE user | Оценивает отклонения CODE количественно | Deviation assessment report | implemented | Action считает нормализованные метрики по Prepare/Develop/Control/Apply, исключает missing metrics, генерирует Markdown report в `build/reports/code/` | `ControlGenerateDeviationReportAction.kt`, `DeviationAssessmentService.kt` |
| IDE user | Просит AI предложить тесты | Control AI test suggestions | implemented | Background task собирает changed files, вызывает AI, обновляет Tool Window и notification | `ControlAiSuggestTestsAction.kt`, `AiAssistantService.kt`, `CodeToolWindow.kt` |
| IDE user | Проверяет готовность изменений | Apply changed files and branch age validation | implemented | Action считает файлы в default changelist, проверяет branch age по lifecycle service, сравнивает с `CODE.yaml`, показывает notification | `ApplyValidateChangesAction.kt`, `CodeBranchLifecycleService.kt` |
| IDE user | Просит AI описание PR | Apply AI PR description | implemented | Background task собирает changed files и coverage info, вызывает AI, обновляет Tool Window | `ApplyAiSuggestPrDescriptionAction.kt`, `AiAssistantService.kt`, `CodeToolWindow.kt` |
| IDE user | Смотрит состояние CODE | Tool Window overview | implemented | Tool Window показывает config sections и последние AI responses | `CodeToolWindow.kt`, `CodeToolWindowFactory.kt`, `plugin.xml` |
| Maintainer / Agent | Создает или валидирует `CODE.md` | Prepare CODE.md LLM action | implemented for CODE.md only | Action анализирует project context и через LLM создает или валидирует `CODE.md`; SPEC generation пока не встроена | `ManageCodeMdAction.kt`, `CodeMdService.kt`, `AiAssistantService.kt` |
| Maintainer / Agent | Получает полную project specification | CODE/SPEC skill workflow | partial | Skill/prompt существуют в `.codex/skills/code-spec-workflow` и `docs/project-spec-agent-prompt.md`; IDE action для `SPEC.md` еще planned | `.codex/skills/code-spec-workflow/SKILL.md`, `docs/project-spec-agent-prompt.md` |

## 3. Product / System Behavior

### IDE UX / User Workflow

- Plugin добавляет toolbar group `CODE` в `MainToolbarRight` с actions для Prepare, Develop, Control, Apply и reload config. Source: `plugin.xml`.
- Startup activity уведомляет о наличии или отсутствии `CODE.yaml`. Source: `CodeStartupActivity.kt`.
- Tool Window `CODE` отображает текущие config values и последние AI responses. Source: `CodeToolWindow.kt`.
- User-visible results в основном доставляются через IntelliJ notifications и `Messages` dialogs. Source: actions in `src/main/kotlin/ru/codeplugin/actions`.

### AI Behavior

- AI включается только если `ai.enabled=true` и заполнены `authKeyBase64`, `authUrl`, `apiUrl`. Source: `AiAssistantService.kt`, `CODE.yaml`.
- OAuth token получается через `POST {authUrl}/api/v2/oauth`, затем chat request идет в `{apiUrl}/api/v1/chat/completions`. Source: `AiAssistantService.kt`.
- Prompts реализованы в Kotlin для PR description, test suggestions, CODE.md generation и CODE.md review. Source: `AiAssistantService.kt`.
- Failure handling возвращает user-facing Russian message и пишет warning log. Source: `AiAssistantService.kt`.
- Risk: `ensureToken()` логирует full OAuth response body; если response содержит token, это может раскрыть секрет. Source: `AiAssistantService.kt`.

### Data / Config Behavior

- `CODE.yaml` schema: `prepare.branch_format`, `prepare.max_branch_age_hours`, `develop.require_code_style_check`, `control.coverage.min_overall`, `control.coverage.report_path`, `apply.max_files_changed`, `ai.*`. Source: `CODE.yaml`, `CodeConfigService.kt`.
- `CODE.yaml` считается пользовательским вводом; при parse error сервис показывает warning и использует defaults. Source: `CodeConfigService.kt`.
- `authKeyBase64` в текущем `CODE.yaml` выглядит как placeholder, но schema содержит credential-like key; реальные значения нельзя копировать в docs/logs. Source: `CODE.yaml`.
- Coverage report path по умолчанию: `build/reports/jacoco/test/jacocoTestReport.xml`. Source: `CODE.yaml`, `CodeConfigService.kt`.

### Build / Platform Behavior

- Build system: Gradle Kotlin DSL, Kotlin JVM `2.1.0`, IntelliJ Platform Gradle plugin `2.7.1`. Source: `build.gradle.kts`.
- Platform target: IntelliJ Community `IC 2025.1.4.1`, sinceBuild `251`, Java/Kotlin JVM 21. Source: `build.gradle.kts`, `gradle.properties`.
- Bundled plugin dependency: `Git4Idea`. Source: `build.gradle.kts`, `plugin.xml`.

## 4. Repository Map

| Area | Paths | Purpose | Read First When | Source |
| --- | --- | --- | --- | --- |
| Project workflow | `CODE.md`, `CODE.yaml`, `docs/SPEC.md` | CODE operational rules, machine config, project specification | Любая задача для LLM/maintainer | `CODE.md`, `CODE.yaml` |
| Plugin descriptor | `src/main/resources/META-INF/plugin.xml` | Registration of actions, services, inspection, Tool Window, plugin metadata | Изменение UX, action ids, plugin capabilities | `plugin.xml` |
| Actions | `src/main/kotlin/ru/codeplugin/actions/*.kt` | User-triggered Prepare/Develop/Control/Apply behavior | Любое изменение IDE action workflow | `rg --files` |
| Services | `src/main/kotlin/ru/codeplugin/services/*.kt` | Config, AI, CODE.md context, branch lifecycle, coverage parsing | Изменение core behavior или shared logic | `rg --files` |
| Generated reports | `build/reports/code/deviation-assessment-*.md` | Markdown reports for CODE quantitative deviation assessment | Проверка результата `Generate Deviation Report` | `DeviationAssessmentService.kt` |
| Startup | `src/main/kotlin/ru/codeplugin/startup/CodeStartupActivity.kt` | Initial config detection/reload notification | Изменение поведения при открытии проекта | `CodeStartupActivity.kt` |
| UI | `src/main/kotlin/ru/codeplugin/ui/*.kt` | Tool Window display and refresh | Изменение отображения config/AI responses | `CodeToolWindow.kt` |
| Inspection | `src/main/kotlin/ru/codeplugin/inspections/NotFormattedInspection.kt`, `src/main/resources/inspectionDescriptions/CodeNotFormatted.html` | Local inspection for CODE formatting | Изменение inspections/PSI behavior | `plugin.xml` |
| Build config | `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/*` | Dependencies, platform, JVM target, plugin version | Изменение dependency/platform/release | `build.gradle.kts` |
| Skill / prompts | `.codex/skills/code-spec-workflow/**`, `docs/project-spec-agent-prompt.md` | Portable CODE/SPEC workflow artifacts | Изменение популяризации CODE или agent workflow | `.codex/skills/code-spec-workflow/SKILL.md` |

## 5. Architecture And Runtime Flows

### Flow: Startup Config Loading

1. Trigger / entrypoint: IntelliJ `postStartupActivity`.
2. Main classes / services: `CodeStartupActivity`, `CodeConfigService`.
3. Data/config used: root `CODE.yaml`, defaults from `CodeConfig`.
4. User-visible result: notification "CODE: конфигурация загружена" or warning about missing `CODE.yaml`.
5. Failure modes: missing project base path, absent config, parse error.
6. Acceptance criteria / verification evidence: run IDE sandbox, open project with valid/missing/broken `CODE.yaml`, observe notification and default fallback.
7. Source files: `plugin.xml`, `CodeStartupActivity.kt`, `CodeConfigService.kt`.

### Flow: Prepare Branch Validation

1. Trigger / entrypoint: action `ru.codeplugin.action.prepare`.
2. Main classes / services: `ValidateBranchNameAction`, `CodeConfigService`, `CodeBranchLifecycleService`, `GitUtil`.
3. Data/config used: `prepare.branch_format`, current Git branch.
4. User-visible result: notification OK/WARNING/ERROR; branch lifecycle mark on success.
5. Failure modes: no project, no Git repo, null branch, invalid regex derived from config.
6. Acceptance criteria / verification evidence: branch matching/non-matching pattern produces correct notification; no Git root produces controlled error.
7. Source files: `plugin.xml`, `ValidateBranchNameAction.kt`, `CodeBranchLifecycleService.kt`, `CODE.yaml`.

### Flow: Develop Format Current File

1. Trigger / entrypoint: action `ru.codeplugin.action.develop`.
2. Main classes / services: `RunDevelopChecksAction`, `CodeConfigService`, `CodeStyleManager`.
3. Data/config used: `develop.require_code_style_check`, current PSI file.
4. User-visible result: info dialog after formatting, warning if no file, info if disabled.
5. Failure modes: no project, no PSI file, formatting exceptions from platform.
6. Acceptance criteria / verification evidence: action formats opened file when enabled; disabled flag prevents formatting.
7. Source files: `RunDevelopChecksAction.kt`, `CODE.yaml`.

### Flow: Control Coverage Check

1. Trigger / entrypoint: action `ru.codeplugin.action.control`.
2. Main classes / services: `ControlCheckCoverageAction`, `JacocoCoverageReader`, `CodeConfigService`.
3. Data/config used: `control.coverage.report_path`, `control.coverage.min_overall`.
4. User-visible result: notification with actual coverage and threshold.
5. Failure modes: no base path, missing/malformed XML, missing counters, invalid threshold.
6. Acceptance criteria / verification evidence: valid JaCoCo XML above/below threshold produces INFORMATION/WARNING; missing report produces warning.
7. Source files: `ControlCheckCoverageAction.kt`, `JacocoCoverageReader.kt`, `CODE.yaml`.

### Flow: Control Quantitative Deviation Assessment

1. Trigger / entrypoint: action `ru.codeplugin.action.deviationReport`.
2. Main classes / services: `ControlGenerateDeviationReportAction`, `DeviationAssessmentService`, `CodeConfigService`, `CodeBranchLifecycleService`, `JacocoCoverageReader`.
3. Data/config used: `prepare.branch_format`, `prepare.max_branch_age_hours`, `develop.require_code_style_check`, `control.coverage.min_overall`, `control.coverage.report_path`, `control.required_checks`, `apply.max_files_changed`, current Git branch, default changelist.
4. User-visible result: notification with integral deviation `F`, worst CODE stage, and generated Markdown report path.
5. Failure modes: no project base path, no Git branch, unknown branch age, missing coverage XML, missing coverage artifact, unreadable report path, empty `control.required_checks`.
6. Acceptance criteria / verification evidence: `coverage_report_exists` is counted as a binary Control metric, `required_checks_configured` is counted as a binary Control metric based on non-empty `control.required_checks`, missing metrics are excluded from calculation, stage weights are renormalized over available metrics, report is written under `build/reports/code/`, and stage vector labels use `xP/dP`, `xD/dD`, `xC/dC`, `xA/dA`.
7. Source files: `ControlGenerateDeviationReportAction.kt`, `DeviationAssessmentService.kt`, `docs/deviation-assessment-user-scenario.md`.

### Flow: Control AI Test Suggestions

1. Trigger / entrypoint: action `ru.codeplugin.action.control.aiTests`.
2. Main classes / services: `ControlAiSuggestTestsAction`, `AiAssistantService`, `CodeToolWindow`.
3. Data/config used: default changelist changed files, `ai.*` config.
4. User-visible result: notification and Tool Window text update.
5. Failure modes: AI disabled, auth failure, API error, no changed files, background task cancellation.
6. Acceptance criteria / verification evidence: with AI disabled user sees configured disabled message; with AI configured Tool Window receives response.
7. Source files: `ControlAiSuggestTestsAction.kt`, `AiAssistantService.kt`, `CodeToolWindow.kt`.

### Flow: Apply Readiness Validation

1. Trigger / entrypoint: action `ru.codeplugin.action.apply`.
2. Main classes / services: `ApplyValidateChangesAction`, `CodeBranchLifecycleService`, `CodeConfigService`, `ChangeListManager`, `GitUtil`.
3. Data/config used: `apply.max_files_changed`, `prepare.max_branch_age_hours`, default changelist, current branch.
4. User-visible result: notification summarizing branch, changed files count, branch age.
5. Failure modes: no Git repo, unknown branch age, multi-root Git limitation, default changelist only.
6. Acceptance criteria / verification evidence: changed file count over/under threshold produces correct notification; branch age unknown handled without crash.
7. Source files: `ApplyValidateChangesAction.kt`, `CodeBranchLifecycleService.kt`, `CODE.yaml`.

### Flow: Apply AI PR Description

1. Trigger / entrypoint: action `ru.codeplugin.action.apply.aiPrDescription`.
2. Main classes / services: `ApplyAiSuggestPrDescriptionAction`, `AiAssistantService`, `JacocoCoverageReader`, `CodeToolWindow`.
3. Data/config used: default changelist changed files, coverage report, `ai.*`.
4. User-visible result: Tool Window receives commit title / PR draft; notification says response updated.
5. Failure modes: AI disabled/auth/API error, missing coverage report, no changed files.
6. Acceptance criteria / verification evidence: response appears in Tool Window; missing coverage is stated instead of crashing.
7. Source files: `ApplyAiSuggestPrDescriptionAction.kt`, `AiAssistantService.kt`, `CodeToolWindow.kt`.

### Flow: CODE.md Generation / Validation

1. Trigger / entrypoint: action `ru.codeplugin.action.codeMd`.
2. Main classes / services: `ManageCodeMdAction`, `CodeMdService`, `AiAssistantService`.
3. Data/config used: project file map, excerpts, existing `CODE.md`, `ai.*`.
4. User-visible result: created `CODE.md` or validation notification.
5. Failure modes: AI disabled, LLM/API failure, `CODE.md` appears during generation, project context too generic.
6. Acceptance criteria / verification evidence: existing `CODE.md` is not overwritten; missing file generated only after LLM response; AI disabled shows warning.
7. Source files: `ManageCodeMdAction.kt`, `CodeMdService.kt`, `AiAssistantService.kt`.

## 6. CODE / PDCA Contract

- Use `CODE.md` as the operational agreement for reading, editing, checking, and handing off code in this repository.
- Prepare: start from `plugin.xml`, `CODE.yaml`, target action/service, and `git status`; avoid full-project scans unless task scope requires it.
- Develop: keep changes inside the owning action/service/UI/inspection layer; introduce shared abstractions only when duplication or cross-action behavior justifies it.
- Control: use `.\gradlew.bat test`, `.\gradlew.bat build`, `.\gradlew.bat verifyPlugin`, and manual `runIde` checks when behavior touches IDE UX.
- Apply: final handoff must state changed files, PDCA stage, checks run/skipped, risks, and whether `CODE.md` / `docs/SPEC.md` need updates.

## 7. Implementation Plan For Future Agents

| Task Type | Read First | Likely Files To Change | Checks | Risks |
| --- | --- | --- | --- | --- |
| Add/modify IDE action | `plugin.xml`, target file in `actions/`, related service | `src/main/kotlin/ru/codeplugin/actions/*.kt`, `plugin.xml` | `.\gradlew.bat build`, `.\gradlew.bat runIde` | Action id drift, UI thread blocking, unclear notifications |
| Improve config handling | `CODE.yaml`, `CodeConfigService.kt`, consumers | `CodeConfigService.kt`, `CODE.yaml`, affected actions | `.\gradlew.bat test` if tests added, manual bad YAML check | Stale defaults, unvalidated user input, credentials in config |
| Harden AI behavior | `AiAssistantService.kt`, AI actions, `CodeToolWindow.kt` | `AiAssistantService.kt`, `ControlAiSuggestTestsAction.kt`, `ApplyAiSuggestPrDescriptionAction.kt` | Manual disabled/auth failure/API failure checks | Secret leakage, logging tokens, blocking/cancellation gaps |
| Add SPEC generation to plugin | `ManageCodeMdAction.kt`, `CodeMdService.kt`, `AiAssistantService.kt`, this spec | New or existing Prepare action/service files | `.\gradlew.bat build`, manual runIde diff/preview | Overwriting docs, too much context, no preview |
| Improve coverage parsing | `JacocoCoverageReader.kt`, `ControlCheckCoverageAction.kt` | `JacocoCoverageReader.kt`, tests if added | Unit tests with valid/malformed XML | XML parser security, missing counters, threshold validation |
| Improve deviation assessment | `DeviationAssessmentService.kt`, `ControlGenerateDeviationReportAction.kt`, `docs/deviation-assessment-user-scenario.md` | `DeviationAssessmentService.kt`, action/report docs, `CODE.yaml` if targets change | `.\gradlew.bat build`, manual action run in IDE | Incorrect normalization, missing metrics treated as pass, report leaking secrets |
| Improve Tool Window UX | `CodeToolWindow.kt`, `CodeToolWindowFactory.kt`, AI actions | `ui/*.kt`, action refresh calls | `.\gradlew.bat runIde` | Stale UI state, unreadable long AI text, mojibake |
| Add tests | Build config, target service/action, IntelliJ test framework docs | `src/test/**`, target files if refactoring for testability | `.\gradlew.bat test` | No current test roots, platform test setup complexity |
| Release/plugin metadata | `plugin.xml`, `build.gradle.kts`, `gradle.properties` | Metadata/build files | `.\gradlew.bat verifyPlugin`, `.\gradlew.bat buildPlugin` | Incorrect sinceBuild, missing descriptions/icons |

## 8. Context Budget Plan

Minimal context for most tasks:

- `CODE.md` for operational rules.
- `docs/SPEC.md` for capabilities and runtime flows.
- `src/main/resources/META-INF/plugin.xml` for plugin registration.
- `CODE.yaml` for process config schema/example.
- One target action/service/UI/inspection file.

Expand context when:

- behavior crosses action/service boundaries;
- config schema or defaults change;
- AI prompt/transport behavior changes;
- Tool Window state or notifications depend on action results;
- build/platform metadata changes.

Avoid unless needed:

- `build/`, `.gradle/`, `.idea/`, `.intellijPlatform/`, `.kotlin/`;
- generated coverage reports except as verification evidence;
- full source scan after target entrypoint is known.

## 9. Quality Bar

Functional quality:

- Actions degrade gracefully when project, Git repo, current file, config, coverage report, or AI credentials are absent.
- User-visible notifications/dialogs explain the next action.
- Long-running operations use background tasks and do not freeze IDE.
- `CODE.yaml` values are validated or treated as untrusted input.

Documentation quality:

- `CODE.md` remains operational and compact enough for LLM context.
- `docs/SPEC.md` remains product/system oriented and does not duplicate the full CODE workflow.
- Changes to capabilities, runtime flows, config schema, AI behavior, or plugin actions update this spec.

LLM-readiness:

- Key claims include source paths.
- Future agents can choose read-first files by task type.
- Risks and unverified assumptions are explicit.
- Quantitative deviation reports separate measured deviations from missing data.

Security/privacy:

- Do not copy real `authKeyBase64`, OAuth tokens, access tokens, or full API responses into docs/logs.
- Treat `CODE.yaml` credential fields as schema/placeholders only.

## 10. Open Questions / Gaps

- No `README.md` exists; product-facing installation/usage docs are not captured in the repository. Source: `rg --files`.
- No `src/test/` files are present; automated coverage for services/actions is unclear. Source: `rg --files`.
- `AiAssistantService.ensureToken()` logs OAuth response body; this may leak token data and should be hardened. Source: `AiAssistantService.kt`.
- `CODE.yaml` includes credential-like key `authKeyBase64`; current value appears placeholder, but project needs a safer documented secret strategy. Source: `CODE.yaml`.
- `JacocoCoverageReader` uses default `DocumentBuilderFactory` without explicit XXE hardening flags. Source: `JacocoCoverageReader.kt`.
- Apply validation currently uses default changelist and first Git repository; multi-root/changelist behavior should be clarified. Source: `ApplyValidateChangesAction.kt`, `ValidateBranchNameAction.kt`.
- `ManageCodeMdAction` supports `CODE.md` generation/validation only; SPEC generation/validation is not yet integrated into plugin runtime. Source: `ManageCodeMdAction.kt`.
- Deviation assessment currently uses MVP equal weights and local IDE/Git/report data only; retrospective/expert weights are not implemented yet. Source: `DeviationAssessmentService.kt`.
- `CodeNotFormatted.html` should be reviewed for project-specific inspection guidance before release. Source: `inspectionDescriptions/CodeNotFormatted.html`.

## 11. Agent Handoff Template

Summary:

- PDCA/CODE stage:
- Product capability:
- User-visible behavior:

Changed files:

- `path`: what changed and why

Checks:

- Ran:
- Skipped:
- Evidence:

Risks:

- Security/privacy:
- IDE UX:
- Build/platform:
- Config/backward compatibility:

Docs:

- `CODE.md` updated: yes/no
- `docs/SPEC.md` updated: yes/no
- Other docs:

Next PDCA step:

- Prepare / Develop / Control / Apply:
