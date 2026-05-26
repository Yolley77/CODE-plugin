# Пользовательский сценарий: количественная оценка отклонений CODE

## Назначение

Количественная оценка отклонений CODE — это `Control`-ориентированный workflow в IDE, который показывает, насколько текущее состояние работы с кодовой базой отклоняется от целевых правил `CODE.yaml` и этапов `Prepare`, `Develop`, `Control`, `Apply`.

Функция не исправляет проект автоматически. Она собирает локальные факты, нормализует доступные метрики, исключает отсутствующие данные из расчёта и генерирует Markdown-report для review, handoff или следующего LLM-agent run.

## Пользовательский Flow

1. Пользователь открывает проект в IntelliJ IDEA / Android Studio.
2. Plugin загружает `CODE.yaml` или использует defaults через существующий startup/config flow.
3. Пользователь запускает action `Generate Deviation Report`.
4. Action выполняет assessment в background task.
5. `DeviationAssessmentService` собирает доступные метрики по этапам CODE.
6. Missing metrics исключаются из расчёта, а не считаются успешными.
7. Plugin записывает generated report в `build/reports/code/deviation-assessment-<timestamp>.md`.
8. Пользователь получает notification с integral deviation `F`, worst stage и path к report.

## Метрики MVP

| Stage | Metric | Type | Target Source | Missing Data Behavior |
| --- | --- | --- | --- | --- |
| Prepare | Соответствие имени ветки `branch_format` | binary | `prepare.branch_format` | exclude |
| Prepare | Возраст активной ветки | destimulating | `prepare.max_branch_age_hours` | exclude |
| Develop | Проверка code style включена | binary | `develop.require_code_style_check` | available from config |
| Control | Наличие JaCoCo coverage report | binary | `control.coverage.report_path` | available from path |
| Control | JaCoCo coverage | stimulating | `control.coverage.min_overall`, `control.coverage.report_path` | exclude |
| Control | Наличие модели required checks | binary | `control.required_checks` | available from config |
| Apply | Количество изменённых файлов | destimulating | `apply.max_files_changed` | exclude |
| Apply | Возраст ветки перед применением | destimulating | `prepare.max_branch_age_hours` | exclude |

## Математика

- Stimulating metric: `x = min(z / g, 1)`.
- Destimulating metric: если `z == 0`, то `x = 1`; иначе `x = min(g / z, 1)`.
- Binary metric: `x = 1`, если условие выполнено, иначе `0`.
- Отклонение метрики: `d = 1 - x`.
- Состояние этапа: среднее доступных `x` внутри stage.
- Отклонение этапа: `dP = 1 - xP`, `dD = 1 - xD`, `dC = 1 - xC`, `dA = 1 - xA` для Prepare/Develop/Control/Apply.
- Интегральное отклонение: среднее доступных stage deviations.
- Missing metrics не участвуют в среднем; веса перенормируются по доступным данным.

## Generated Report

Report должен быть deterministic и пригодным для handoff:

```markdown
# CODE Deviation Assessment

- Project: ...
- Generated at: ...
- Branch: ...
- Integral deviation F: ...
- Worst stage: ...
- Missing metrics: ...

## Prepare
...

## Develop
...

## Control
...

## Apply
...
```

В отчёте должны быть:

- stage state/deviation в stage-specific notation: `xP/dP`, `xD/dD`, `xC/dC`, `xA/dA`;
- таблица метрик с `actual`, `target`, `normalized`, `deviation`, `missing reason`;
- список missing metrics;
- короткая интерпретация, что `F` нельзя читать без stage vector.

## Edge Cases

- No Git repository: branch metrics и changed files metrics исключаются, report остаётся частичным.
- Branch age unknown: метрики возраста исключаются до прохождения Prepare.
- Missing coverage XML: `coverage_report_exists` получает deviation, а `coverage_overall` исключается и получает missing reason.
- Empty `control.required_checks`: `required_checks_configured` получает deviation, потому что модель обязательных проверок не задана.
- Broken `branch_format`: branch name metric исключается с причиной.
- `develop.require_code_style_check=false`: Develop metric получает deviation, потому что target для MVP — включённая проверка style.
- Missing `CODE.yaml`: используется fallback config из `CodeConfigService`; report должен показывать targets из фактически активной config.
- Multi-root Git: текущий MVP использует первый Git repository, это known limitation.
- Secrets: report не должен включать реальные credential values, OAuth response body или access tokens.

## Acceptance Criteria

- Action доступен из CODE toolbar как `Generate Deviation Report`.
- Action не блокирует UI thread.
- Report создаётся в `build/reports/code/`.
- Missing metrics исключаются из расчёта и явно перечисляются.
- `F` находится в диапазоне `[0, 1]`, если есть хотя бы один доступный stage.
- Worst stage определяется по максимальному stage-specific deviation: `dP`, `dD`, `dC` или `dA`.
- `.\gradlew.bat build` проходит.
- Документация `CODE.md` и `docs/SPEC.md` указывает на новую capability.

## Связь С CODE И SPEC

- `CODE.md` описывает, как разработчик или LLM должны использовать report в цикле `Control`.
- `docs/SPEC.md` фиксирует capability, runtime flow, generated report location и current limitations.
- `CODE.yaml` остаётся источником target values для MVP.

Следующий возможный шаг — добавить expert/retrospective weights в `CODE.yaml` и сохранять историю reports для анализа динамики во времени.
