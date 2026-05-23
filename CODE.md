# CODE.md

## Обзор

Этот документ — рабочее соглашение для проекта `CODE Methodology`.

Он нужен для двух задач:

- помочь разработчику двигаться по циклу PDCA: `Prepare -> Develop -> Control -> Apply`;
- дать LLM-агенту компактный и точный контекст, чтобы снижать объем повторного изучения кодовой базы и повышать точность
  изменений.

Проект — Kotlin-плагин для IntelliJ IDEA / Android Studio. Сам плагин реализует автоматизированное сопровождение PDCA
внутри IDE: читает `CODE.yaml`, проверяет правила процесса, работает с Git, inspections, JaCoCo coverage, Tool Window и
AI-подсказками.

`CODE.md` не заменяет README и не описывает пользовательскую инструкцию плагина. Это документ для разработки самого
плагина: как входить в проект, что читать, как менять код, как проверять результат и как готовить изменения к
применению.

Продуктовая и системная спецификация проекта живет в `docs/SPEC.md`. Используй ее как источник целей, capabilities,
runtime flows и implementation plan, а этот `CODE.md` — как operational layer для движения по кодовой базе через
`Prepare -> Develop -> Control -> Apply`.

## Оглавление

- [1. Prepare — инициация и контекст](#1-prepare--инициация-и-контекст)
- [2. Develop — реализация изменения](#2-develop--реализация-изменения)
- [3. Control — проверка результата](#3-control--проверка-результата)
- [4. Apply — применение и следующий цикл](#4-apply--применение-и-следующий-цикл)

## 1. Prepare — инициация и контекст

`Prepare` отвечает за вход в задачу до изменения кода. На этом этапе нужно понять, какой сценарий PDCA сопровождает
плагин, какие файлы действительно нужны для работы и как будет проверяться результат.

### 1.1 Что делает разработчик

- Формулирует пользовательский сценарий в IDE: что разработчик делает и какой результат ожидает.
- Определяет этап PDCA, который автоматизируется или улучшается.
- Проверяет, какие правила берутся из `CODE.yaml`.
- Смотрит текущий `git status`, чтобы не перетереть чужие изменения.
- Заранее выбирает проверку: unit test, Gradle task, `runIde` или ручной сценарий.

### 1.2 Что делает LLM-агент

- Не сканирует весь проект без причины.
- Сначала определяет stage, entrypoint и минимальный набор файлов.
- Не начинает с переписывания архитектуры.
- Не меняет файлы, не относящиеся к задаче.
- Не откатывает чужие изменения без прямой просьбы.
- До редактирования понимает, какой результат будет считаться готовым.

### 1.3 Минимальный контекст для LLM

Почти для любой задачи достаточно начать с этих источников:

1. `src/main/resources/META-INF/plugin.xml` — регистрация actions, services, inspections и Tool Window.
2. `CODE.yaml` — правила методологии, которые сопровождает плагин.
3. Один целевой слой из карты проекта ниже.
4. Соседние файлы только если без них нельзя понять контракт или поток данных.
5. `git status` — текущие изменения в рабочем дереве.

Если задача локальная, не читай весь `src/main/kotlin`. Расширяй контекст только когда нужно уточнить контракт: config,
VCS, PSI, UI, coverage или AI.

### 1.4 Карта проекта

- `src/main/resources/META-INF/plugin.xml` — регистрация плагина, actions, Tool Window, inspections.
- `src/main/kotlin/ru/codeplugin/startup/CodeStartupActivity.kt` — стартовая проверка проекта и загрузка `CODE.yaml`.
- `src/main/kotlin/ru/codeplugin/services/CodeConfigService.kt` — чтение и хранение конфигурации.
- `src/main/kotlin/ru/codeplugin/services/AiAssistantService.kt` — интеграция с AI API.
- `src/main/kotlin/ru/codeplugin/services/JacocoCoverageReader.kt` — чтение JaCoCo XML.
- `src/main/kotlin/ru/codeplugin/services/CodeBranchLifecycleService.kt` — состояние жизненного цикла ветки.
- `src/main/kotlin/ru/codeplugin/actions` — действия этапов `Prepare`, `Develop`, `Control`, `Apply`.
- `src/main/kotlin/ru/codeplugin/ui/CodeToolWindow.kt` — отображение конфигурации и AI-ответов.
- `src/main/kotlin/ru/codeplugin/inspections/NotFormattedInspection.kt` — инспекция форматирования.
- `src/main/resources/inspectionDescriptions` — описания inspections.

Типовой поток данных:

1. Пользователь открывает проект в IDE.
2. Startup activity ищет и загружает `CODE.yaml`.
3. Actions выполняют проверки и подсказки PDCA.
4. Services читают Git, PSI, coverage, config и AI API.
5. Результаты показываются через notifications и Tool Window.

### 1.5 Быстрый вход по типу задачи

- Startup или загрузка методологии: `plugin.xml`, `CodeStartupActivity.kt`, `CodeConfigService.kt`, `CODE.yaml`.
- Проверка ветки: `ValidateBranchNameAction.kt`, `CodeBranchLifecycleService.kt`, `CODE.yaml`.
- Форматирование и локальная разработка: `RunDevelopChecksAction.kt`, `NotFormattedInspection.kt`, inspection
  descriptions.
- Coverage и качество: `ControlCheckCoverageAction.kt`, `JacocoCoverageReader.kt`, `CODE.yaml`.
- AI-подсказки: конкретный AI action и `AiAssistantService.kt`.
- PR/review/apply: `ApplyValidateChangesAction.kt`, `ApplyAiSuggestPrDescriptionAction.kt`, VCS-related services.
- Tool Window: `CodeToolWindow.kt` и service, откуда берется отображаемое состояние.

### 1.6 Контрольные вопросы Prepare

- Что именно пользователь хочет сделать в IDE?
- Как плагин должен сопровождать этот шаг PDCA?
- Где начинается сценарий: action, startup activity, inspection, Tool Window или service?
- Какие значения из `CODE.yaml` участвуют в сценарии?
- Что должно произойти при отсутствующем Git, отсутствующем config или невалидном regex?
- Какой минимальный результат покажет, что задача решена?

### 1.7 Антипаттерны Prepare

- Читать весь проект до определения entrypoint.
- Начинать с большого refactor без локальной причины.
- Игнорировать существующие изменения в git.
- Считать `CODE.yaml` доверенным вводом.
- Не определить проверку до начала реализации.

## 2. Develop — реализация изменения

`Develop` отвечает за изменение кода. На этом этапе нужно сделать минимальный понятный diff, который улучшает
автоматизированное сопровождение PDCA и не ухудшает устойчивость IDE.

### 2.1 Что делает разработчик

- Реализует изменение в границах текущего action, service, inspection или UI-компонента.
- Следует стилю соседнего Kotlin-кода.
- Использует IntelliJ Platform APIs вместо самодельных обходов, когда API подходит.
- Выносит повторяемую доменную логику в services, но не создает абстракции заранее.
- Обрабатывает ошибки там, где их можно объяснить пользователю.

### 2.2 Что делает LLM-агент

- Генерирует код внутри локального контракта, а не додумывает новую архитектуру.
- Проверяет, кто владеет поведением: action, service, inspection или Tool Window.
- Не добавляет новую зависимость, если задача решается существующим стеком.
- Не смешивает широкий refactor с функциональным изменением.
- Сохраняет diff таким, чтобы reviewer видел причину каждой правки.

### 2.3 Общие правила реализации

- Не блокируй UI thread сетевыми запросами, файловым I/O или тяжелыми PSI-операциями.
- Используй background tasks для долгих операций.
- Обрабатывай null, отсутствующие файлы, невалидный config и ошибки API штатно.
- Не логируй секреты, токены, OAuth-ответы, реальные значения `authKeyBase64` или полные тела AI/API responses.
- Пользовательские сообщения должны помогать сделать следующий шаг.
- Тексты в Kotlin, `plugin.xml` и resources должны быть читаемыми и сохраненными в UTF-8.

### 2.4 Конфигурация

- `CODE.yaml` — пользовательский ввод, а не внутренняя константа.
- Валидируй branch patterns, numeric thresholds, file limits, paths и AI settings перед использованием.
- Сохраняй обратную совместимость, если breaking change не является явной частью задачи.
- Для секретов предпочитай environment variables, IDE secure storage или отдельные настройки, а не проектный YAML.
- Ошибка config должна объяснять, какое поле неверно и что ожидалось.

### 2.5 AI

- Prompts должны быть явными и привязанными к конкретному action.
- AI-запросы должны иметь timeout и cancellation-aware поведение.
- Разделяй transport failures, authentication failures, rate limits и model/API errors.
- В пользовательских ошибках показывай достаточно информации для действия, но не раскрывай секреты.
- Не отправляй в AI больше project context, чем нужно для конкретного действия.

### 2.6 VCS

- Не предполагай один Git root.
- Явно выбирай changelist или набор changes, с которым работает action.
- Отсутствие Git-репозитория должно быть штатным состоянием, а не падением.
- Если поведение ограничено default changelist или первым repository, это должно быть осознанным и понятным
  пользователю.

### 2.7 Inspections и PSI

- Inspections запускаются часто, поэтому не должны делать дорогую работу на каждом проходе.
- PSI-операции должны выполняться в корректном read/write action контексте.
- Избегай форматирования или повторного парсинга больших файлов в горячем пути.
- Inspection description должен быть специфичным для CODE Methodology, а не шаблонным.

### 2.8 Файлы Develop

Для изменений в `Develop` смотри в первую очередь:

- `RunDevelopChecksAction.kt`
- `NotFormattedInspection.kt`
- `CodeToolWindow.kt`
- `CodeConfigService.kt`
- `AiAssistantService.kt`

### 2.9 Контрольные вопросы Develop

- Можно ли решить задачу без новой абстракции?
- Улучшает ли изменение автоматизированное сопровождение PDCA?
- Выполняется ли тяжелая работа вне UI thread?
- Есть ли безопасная обработка null, отсутствующих файлов и ошибок API?
- Не появился ли лог с чувствительными данными?
- Не ухудшилась ли производительность inspection или Tool Window?

### 2.10 Антипаттерны Develop

- Хранить секреты в `CODE.yaml`.
- Логировать OAuth/API response body целиком.
- Делать network или file I/O из UI thread.
- Добавлять общий helper без повторного использования.
- Менять поведение нескольких PDCA-этапов в одном маленьком fix.

## 3. Control — проверка результата

`Control` отвечает за доказательство результата. На этом этапе нужно подтвердить, что изменение работает, не ломает
IDE-эргономику, не ухудшает безопасность и действительно помогает сопровождать PDCA.

### 3.1 Что делает разработчик

- Проверяет фактическое поведение, а не только отсутствие исключений.
- Сначала запускает узкую проверку, затем более широкую.
- Проверяет ошибки и edge cases, особенно для config, VCS, coverage и AI.
- Фиксирует, какие проверки выполнены.

### 3.2 Что делает LLM-агент

- Не называет задачу завершенной без проверки или явного объяснения ограничения.
- Не скрывает failing tests или warnings, которые относятся к изменению.
- В финальном ответе пишет, что проверено, что не проверено и почему.
- Не тратит контекст на полный пересказ проекта после локальной правки.

### 3.3 Команды Control

Базовые команды для Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat verifyPlugin
.\gradlew.bat runIde
```

Когда использовать:

- `test` — для unit-тестов парсеров, правил и сервисов.
- `build` — для общей компиляции и базовой проверки проекта.
- `verifyPlugin` — перед публикацией или изменением plugin metadata/API.
- `runIde` — для проверки реального поведения в IDE sandbox.

Если проверка не запускалась, это нужно явно написать в ответе или PR.

### 3.4 Что проверять

- `CODE.yaml` loading, defaults, validation errors и missing file behavior.
- Branch name validation для разрешенных и запрещенных имен.
- Branch lifecycle tracking и branch age limits.
- Changelist/file-count validation.
- JaCoCo XML parsing, включая missing counters и malformed files.
- Tool Window refresh после config reload и AI responses.
- AI error paths: missing credentials, auth failure, timeout, rate limit, invalid response.
- Inspection behavior на formatted и unformatted files.

### 3.5 Ручная проверка в IDE

Когда меняется plugin behavior, проверь в IDE sandbox, если это практически возможно:

- Плагин загружается без startup errors.
- Toolbar actions видны и enabled только когда это уместно.
- Notifications читаемы и не содержат mojibake.
- Tool Window обновляется после actions и config reload.
- Долгие операции не замораживают IDE.
- Error messages ведут пользователя к следующему действию.

### 3.6 Файлы Control

Для изменений в `Control` смотри в первую очередь:

- `ControlCheckCoverageAction.kt`
- `ControlAiSuggestTestsAction.kt`
- `JacocoCoverageReader.kt`
- `AiAssistantService.kt`
- `CodeConfigService.kt`
- `CODE.yaml`

### 3.7 Контрольные вопросы Control

- JaCoCo XML parser защищен от XXE?
- Coverage threshold валиден и находится в ожидаемом диапазоне?
- AI-запросы имеют timeout и понятные ошибки?
- В логах нет токенов, секретов и полных OAuth/API response body?
- Проверена ли кодировка пользовательских сообщений?
- Доказано ли, что изменение поддерживает нужный этап PDCA?

### 3.8 Антипаттерны Control

- Ограничиться словами "должно работать" без проверки.
- Проверять только happy path для пользовательского config.
- Игнорировать ошибки сети и авторизации AI.
- Запускать тяжелые проверки без необходимости для документационной правки.
- Не указать причину, если тесты не запускались.

## 4. Apply — применение и следующий цикл

`Apply` отвечает за превращение изменения в понятный результат: review, PR, публикацию, документацию или вход в
следующий PDCA-цикл.

### 4.1 Что делает разработчик

- Проверяет размер diff и список файлов.
- Готовит описание изменения из фактического diff, а не из предположений.
- Указывает проверки, риски и связь с этапом PDCA.
- Обновляет документацию, если изменился workflow или config.
- Обновляет `docs/SPEC.md`, если изменились capabilities, runtime flows, acceptance criteria или implementation plan.
- Планирует следующий цикл только после завершения текущего.

### 4.2 Что делает LLM-агент

Финальный ответ должен быть коротким и полезным:

- какие файлы изменены;
- какой этап PDCA усилен;
- что проверено;
- что не проверено и почему;
- какой остаточный риск или следующий шаг есть, если он важен.

Не смешивай реализованные изменения с идеями на будущее. Если задача была только на документ, не запускай тяжелые
Gradle-проверки без необходимости.

### 4.3 Формат PR

Для заметных изменений используй такой шаблон:

```markdown
Что изменилось:

Зачем:

Этап PDCA:

Проверка:

Риски и откат:
```

### 4.4 Release readiness

Перед публикацией или передачей plugin build:

- Запусти `buildPlugin` и `verifyPlugin`.
- Проверь plugin metadata в `plugin.xml`.
- Убедись, что inspection descriptions существуют и читаются нормально.
- Подтверди, что supported IDE versions заданы намеренно.
- Проверь, что examples не содержат реальных credentials.
- Обнови README или docs, если изменился пользовательский workflow.

### 4.5 Файлы Apply

Для изменений в `Apply` смотри в первую очередь:

- `ApplyValidateChangesAction.kt`
- `ApplyAiSuggestPrDescriptionAction.kt`
- `CodeBranchLifecycleService.kt`
- `AiAssistantService.kt`
- `plugin.xml`
- `README.md`, `CODE.md`, `CODE.yaml`

### 4.6 Контрольные вопросы Apply

- Изменение относится к понятному этапу PDCA?
- Список файлов в diff соответствует задаче?
- Есть ли проверка или честное объяснение, почему ее нет?
- Нужно ли обновить документацию, `CODE.yaml` example или plugin metadata?
- Нужно ли обновить `docs/SPEC.md`, если изменилось поведение продукта или план реализации?
- Multi-root Git проекты и changelists обработаны явно, если задача касается VCS?
- Ошибки VCS понятны пользователю, а не выглядят как сбой плагина?

### 4.7 Приоритеты следующих циклов

Если нет более срочной задачи, планируй следующие изменения в таком порядке:

1. Безопасность AI и конфигов: секреты, логи, timeout, ошибки.
2. Безопасный парсинг `CODE.yaml` и JaCoCo XML.
3. Тесты для правил, парсеров и сервисов.
4. Корректная работа с несколькими Git roots и changelists.
5. Производительность inspections и PSI-операций.
6. UX Tool Window, reload состояния и качество текстов.
7. README и пользовательская документация.

### 4.8 Антипаттерны Apply

- Отдавать изменение без указания проверки.
- Прятать остаточные риски.
- Описывать PR шире, чем реальный diff.
- Включать unrelated изменения.
- Забывать обновить документацию при изменении workflow.

### 4.9 Принцип завершения

CODE Methodology должна помогать разработчику двигаться по циклу `Prepare -> Develop -> Control -> Apply` без догадок.
Любое изменение в кодовой базе должно сохранять это свойство: ясная подготовка, безопасная реализация, проверяемый
результат и понятное применение.
