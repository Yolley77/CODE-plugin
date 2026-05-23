package ru.codeplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@Service(Service.Level.PROJECT)
class AiAssistantService(private val project: Project) {

    private val log = Logger.getInstance(AiAssistantService::class.java)
    private val httpClient: HttpClient = HttpClient.newBuilder().build()

    @Volatile private var accessToken: String? = null
    @Volatile private var tokenExpiresAt: Instant? = null

    @Volatile var isPrLoading: Boolean = false
    @Volatile var isTestsLoading: Boolean = false

    @Volatile private var lastPrDescription: String? = null
    @Volatile private var lastTestsSuggestion: String? = null

    fun getLastPrDescription(): String? = lastPrDescription
    fun getLastTestsSuggestion(): String? = lastTestsSuggestion
    fun setLastPrDescription(text: String?) { lastPrDescription = text }
    fun setLastTestsSuggestion(text: String?) { lastTestsSuggestion = text }

    private fun cfgAi() = project.service<CodeConfigService>().cfg().ai

    private fun isEnabled(): Boolean {
        val cfg = cfgAi()
        return cfg.enabled && cfg.authKeyBase64.isNotBlank() &&
                cfg.authUrl.isNotBlank() && cfg.apiUrl.isNotBlank()
    }

    fun isConfigured(): Boolean = isEnabled()

        /** Обновляем токен GigaChat при необходимости */
        private fun ensureToken(): Boolean {
            val ai = cfgAi()
            val now = Instant.now()

            if (accessToken != null && tokenExpiresAt != null && now.isBefore(tokenExpiresAt!!.minusSeconds(60))) {
                return true
            }

            return try {
                val uri = URI.create(ai.authUrl.trimEnd('/') + "/api/v2/oauth")
                val scopeValue = ai.scope.ifBlank { "GIGACHAT_API_PERS" }
                val body = "scope=" + URLEncoder.encode(scopeValue, StandardCharsets.UTF_8)

                val authHeader = ai.authScheme.trim() + " " + ai.authKeyBase64.trim()

                val request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("RqUID", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                // Чтобы видеть, что именно не нравится GigaChat
                log.warn("GigaChat OAuth response: ${response.statusCode()} ${response.body()}")

                if (response.statusCode() !in 200..299) {
                    false
                } else {
                    val obj = JSONObject(response.body())
                    val token = obj.getString("access_token")
                    val exp = obj.optLong("expires_at", 0L)
                    accessToken = token
                    tokenExpiresAt = if (exp > 0) {
                        Instant.ofEpochSecond(exp)
                    } else {
                        now.plusSeconds(30 * 60)
                    }
                    true
                }
            } catch (e: Exception) {
                log.warn("GigaChat OAuth exception", e)
                false
            }
        }

    /** Низкоуровневый вызов GigaChat chat/completions */
    private fun callGigaChat(prompt: String): String {
        if (!ensureToken()) {
            throw IllegalStateException("GigaChat: не удалось получить access token")
        }

        val ai = cfgAi()
        val token = accessToken ?: throw IllegalStateException("GigaChat: token == null")

        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", prompt)
            )

        val body = JSONObject()
            .put("model", ai.model.ifBlank { "GigaChat-2" })
            .put("messages", messages)

        val uri = URI.create(ai.apiUrl.trimEnd('/') + "/api/v1/chat/completions")

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("GigaChat API error ${response.statusCode()}: ${response.body()}")
        }

        val obj = JSONObject(response.body())
        val choices = obj.optJSONArray("choices")
            ?: throw IllegalStateException("GigaChat: поле choices отсутствует")

        if (choices.length() == 0) {
            throw IllegalStateException("GigaChat: пустой массив choices")
        }

        val message = choices.getJSONObject(0).getJSONObject("message")
        return message.getString("content")
    }

    /** PR: описание + commit title + рекомендация */
    fun suggestPrDescription(diffSummary: String, coverageInfo: String): String {
        val disabledMsg = "AI-ассистент (GigaChat) отключён или не настроен. " +
                "Проверьте секцию ai в CODE.yaml."
        if (!isEnabled()) {
            lastPrDescription = disabledMsg
            return disabledMsg
        }

        isPrLoading = true
        return try {
            val prompt = buildPrPrompt(diffSummary, coverageInfo)
            val result = callGigaChat(prompt)
            lastPrDescription = result
            result
        } catch (e: Exception) {
            log.warn("GigaChat PR description failed", e)
            val msg = "Не удалось получить ответ от GigaChat: ${e.message}"
            lastPrDescription = msg
            msg
        } finally {
            isPrLoading = false
        }
    }

    /** Тесты: сценарии + примеры реализации тестов */
    fun suggestTests(changesSummary: String, uncoveredAreas: List<String>): String {
        val disabledMsg = "AI-ассистент (GigaChat) отключён или не настроен."
        if (!isEnabled()) {
            lastTestsSuggestion = disabledMsg
            return disabledMsg
        }

        isTestsLoading = true
        return try {
            val prompt = buildTestsPrompt(changesSummary, uncoveredAreas)
            val result = callGigaChat(prompt)
            lastTestsSuggestion = result
            result
        } catch (e: Exception) {
            log.warn("GigaChat tests suggestion failed", e)
            val msg = "Не удалось получить ответ от GigaChat: ${e.message}"
            lastTestsSuggestion = msg
            msg
        } finally {
            isTestsLoading = false
        }
    }

    fun generateCodeMd(projectContext: String): String {
        if (!isEnabled()) {
            return "AI-ассистент (GigaChat) отключен или не настроен. Проверьте секцию ai в CODE.yaml."
        }

        return try {
            callGigaChat(buildCodeMdGenerationPrompt(projectContext))
        } catch (e: Exception) {
            log.warn("GigaChat CODE.md generation failed", e)
            "Не удалось получить CODE.md от GigaChat: ${e.message}"
        }
    }

    fun reviewCodeMd(projectContext: String, existingCodeMd: String): String {
        if (!isEnabled()) {
            return "AI-ассистент (GigaChat) отключен или не настроен. Проверьте секцию ai в CODE.yaml."
        }

        return try {
            callGigaChat(buildCodeMdReviewPrompt(projectContext, existingCodeMd))
        } catch (e: Exception) {
            log.warn("GigaChat CODE.md review failed", e)
            "Не удалось получить ревью CODE.md от GigaChat: ${e.message}"
        }
    }

    // ----- промпты (как мы уже согласовали) -----

    private fun buildPrPrompt(diffSummary: String, coverageInfo: String): String =
        """
        Ты — помощник ревьюера кода на этапе Apply методологии CODE.

        На основе описания изменений и информации о покрытии сформируй русскоязычный черновик Pull Request.
        Ответ должен быть готов к вставке в PR без перевода и ручной перестройки структуры.

        Важно:
        - Пиши только на русском, кроме технических имен файлов, классов, команд и branch/commit identifiers.
        - Не выдумывай измененные файлы, тесты, задачи или результаты проверок.
        - Если информации недостаточно, явно напиши "Не указано" или "Нужно уточнить".
        - Рекомендация должна быть практичной: готово к review, нужно разбить PR, нужны тесты, есть риск по coverage и т.п.
        - Не добавляй общие фразы без привязки к переданному diff/coverage.

        Предложи название коммита в формате:
        ${'$'}{issue}-${'$'}{slug}: краткое описание на русском
        Например: AND-123-login: обработка ошибок авторизации

        Описание изменений:
        $diffSummary

        Информация о покрытии тестами:
        $coverageInfo

        Структурируй ответ строго в следующем виде:

        Название коммита:
        ISSUE-SLUG: краткое описание

        Краткое описание:
        - ...

        Зачем:
        - ...

        Что изменилось:
        - ...

        Проверка:
        - ...

        Риски:
        - ...

        Рекомендация:
        - ...
        """.trimIndent()

    private fun buildTestsPrompt(changesSummary: String, uncoveredAreas: List<String>): String =
        """
        Ты — помощник по тестированию на этапе Control методологии CODE.

        На основе описания изменений и областей без покрытия предложи тестовые сценарии.

        Важно:
        - Не выдумывай классы и API, если их нет в описании изменений. Если точных классов нет, предложи сценарий и укажи "пример адаптировать под фактический класс".
        - Сначала перечисли, что именно нужно проверить, затем дай пример теста только там, где хватает контекста.
        - Если проектный стек тестов неочевиден, не утверждай JUnit5 как факт; пометь как предположение.
        - Приоритизируй edge cases, ошибки конфигурации, отсутствующие файлы, сетевые/API ошибки и регрессии пользовательского workflow.
        - Ответ должен быть на русском.

        Описание изменений:
        $changesSummary

        Области без покрытия:
        ${uncoveredAreas.joinToString(separator = "\n- ", prefix = "- ")}

        Формат ответа:
        ## Рекомендуемые тесты

        1. Сценарий: ...
           Почему важен: ...
           Где проверять: ...
           Пример теста, если хватает контекста:
           ```kotlin
           @Test
           fun `...`() {
               // ...
           }
           ```

        ## Что уточнить
        - ...
        """.trimIndent()

    private fun buildCodeMdGenerationPrompt(projectContext: String): String =
        """
        Ты — senior developer experience engineer и LLM coding workflow architect.

        На основе контекста проекта сгенерируй полный файл CODE.md на русском языке.

        Назначение CODE.md:
        1. Помогать разработчику двигаться по циклу PDCA: Prepare -> Develop -> Control -> Apply.
        2. Давать LLM-агенту компактный, точный контекст проекта, чтобы снижать расход токенов на повторное изучение кодовой базы и повышать точность генерации кода.
        3. Быть рабочим соглашением для разработки проекта, а не пользовательским README.

        Требования к результату:
        - Верни только Markdown-содержимое файла CODE.md, без пояснений вокруг.
        - Пиши на русском.
        - Документ должен быть project-specific: используй реальные файлы, директории, entrypoints, build/test commands и риски из контекста.
        - Не выдумывай команды, архитектуру или директории. Если что-то не подтверждено, явно пометь как предположение.
        - Не вставляй длинные куски исходного кода.
        - Сделай документ достаточно компактным, чтобы LLM могла часто загружать его в контекст.
        - Документ должен быть полезен для первого входа в кодовую базу: по нему должно быть понятно, где искать UI, DI, navigation, network/API, storage/database, domain/business logic, tests и build config, если эти области есть в проекте.
        - Не пиши общие фразы вроде "реализует функционал", "проводит рефакторинг", "проверяет тесты", если рядом нет конкретных файлов, команд, модулей или правил этого проекта.
        - Не используй CODE.yaml как единственный источник фактов о проекте. CODE.yaml описывает методологию; архитектуру и команды бери из build files, file map и excerpts.
        - Если контекста недостаточно, добавь короткую строку "Нужно уточнить:" с конкретным файлом/областью, но не заполняй пробел общей рекомендацией.
        - В каждом PDCA-разделе должны быть конкретные project facts: paths, modules/packages, commands, risks или decision rules.
        - По стилю и полезности документ должен быть похож на хороший CODE.md для самого плагина CODE Methodology: сначала объяснить роль документа, затем дать карту проекта, быстрый вход по типам задач, правила для разработчика и LLM, проверки и Apply-handoff.
        - Не делай "минимальный контекст для LLM" блоком с фрагментом CODE.yaml или Gradle ext. Это должен быть список файлов/директорий, которые LLM должна прочитать первыми для конкретных типов задач.

        Перед написанием мысленно извлеки из контекста:
        - основные модули и директории;
        - entrypoints приложения/плагина/сервера;
        - build system и реальные команды;
        - ключевые зависимости и что они означают для разработки;
        - где живут UI, API/network, database/storage, DI, navigation, domain logic и tests;
        - recurring risks: secrets, generated files, migration risks, flaky/manual checks, platform-specific behavior.

        Обязательная структура:

        # CODE.md

        ## Обзор

        ## Оглавление

        ## 1. Prepare — инициация и контекст

        ## 2. Develop — реализация изменения

        ## 3. Control — проверка результата

        ## 4. Apply — применение и следующий цикл

        Обязательные подразделы:

        В "Обзор":
        - что это за проект;
        - что CODE.md является рабочим соглашением, а не README;
        - как он помогает человеку и LLM;
        - какие факты о проекте были обнаружены.

        В "Prepare":
        - "Карта проекта" с реальными директориями;
        - "Быстрый вход по типу задачи" с конкретными путями;
        - "Минимальный контекст для LLM" как список файлов для первичного чтения;
        - "Контрольные вопросы";
        - "Антипаттерны".

        В "Develop":
        - правила разработки по областям проекта: UI, DI, navigation, API/network, storage/database, domain, tests, build config — только для найденных областей;
        - конкретные source roots и representative files;
        - правила для LLM;
        - антипаттерны.

        В "Control":
        - реальные команды проверки, что запустить для проверки работоспособности;
        - test roots;
        - ручные проверки для UI/mobile/IDE, если применимо;
        - что делать, если проверка не запускалась.

        В "Apply":
        - PR/handoff template на русском;
        - что указать в финальном ответе;
        - риски и следующий PDCA-цикл.

        Дополнительные подразделы можно добавлять только если они полезны для проекта:
        - Что делает разработчик
        - Что делает LLM-агент
        - Команды / Проверки

        Минимальная планка качества:
        - "Prepare" содержит карту проекта и список конкретных файлов, с которых начинать разные типы задач.
        - "Develop" содержит проектные правила внесения изменений по основным областям кода, а не универсальный совет "следуй стилю".
        - "Control" содержит реальные команды проверки и ручные проверки, если проект UI/mobile/IDE.
        - "Apply" содержит конкретный handoff/PR workflow и ограничения, выведенные из проекта.
        - Если документ получился короче 120 строк, скорее всего он слишком общий: расширь его конкретикой из контекста.
        - Минимум 70% bullets должны содержать конкретный путь, команду, модуль, слой, зависимость, риск или decision rule этого проекта.
        - В документе не должно быть разделов, которые применимы к любому проекту без изменений.

        Контекст проекта:
        $projectContext
        """.trimIndent()

    private fun buildCodeMdReviewPrompt(projectContext: String, existingCodeMd: String): String =
        """
        Ты — reviewer документации для developer workflow и LLM coding agents.

        Оцени существующий CODE.md на русском языке для проекта. Нужно проверить, помогает ли он:
        1. Разработчику двигаться по PDCA: Prepare -> Develop -> Control -> Apply.
        2. LLM-агенту снижать расход токенов и точнее генерировать изменения.
        3. Понимать реальную структуру проекта, entrypoints, команды проверки и риски.

        Верни только список практичных улучшений в Markdown. Не переписывай весь CODE.md, если в этом нет необходимости.
        Если документ уже хороший, напиши кратко, что он покрывает, и предложи максимум 3 точечных улучшения.
        Оцени строго: если документ в основном общий, без конкретных путей, команд, модулей и правил проекта, прямо скажи, что он не выполняет задачу CODE.md.
        Не принимай перечисление зависимостей из Gradle за полноценное описание кодовой базы: нужны entrypoints, области кода, команды, workflow и риски.
        Если документ не выполняет задачу, предложи структуру исправления по разделам Prepare, Develop, Control, Apply и назови конкретные файлы/директории из контекста, которые нужно добавить.
        Проверь отдельно:
        - есть ли карта проекта;
        - есть ли быстрый вход по типам задач;
        - есть ли конкретные source roots и representative files;
        - есть ли реальные команды проверки;
        - есть ли русская структура Apply/handoff;
        - может ли LLM использовать документ как стартовый контекст без повторного полного обхода проекта.

        Формат ответа:
        ## Оценка CODE.md

        ## Что улучшить

        ## Риски текущей версии

        ## Рекомендуемый следующий шаг

        Контекст проекта:
        $projectContext

        Существующий CODE.md:
        ```markdown
        $existingCodeMd
        ```
        """.trimIndent()
}
