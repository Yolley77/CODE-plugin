package ru.codeplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Service(Service.Level.PROJECT)
class AiAssistantService(private val project: Project) {

    private val log = Logger.getInstance(AiAssistantService::class.java)
    private val httpClient: HttpClient = HttpClient.newBuilder().build()

    @Volatile var isPrLoading: Boolean = false
    @Volatile var isTestsLoading: Boolean = false

    private var lastPrDescription: String? = null
    private var lastTestsSuggestion: String? = null

    fun getLastPrDescription(): String? = lastPrDescription
    fun getLastTestsSuggestion(): String? = lastTestsSuggestion
    fun setLastPrDescription(text: String?) { lastPrDescription = text }
    fun setLastTestsSuggestion(text: String?) { lastTestsSuggestion = text }

    private fun cfg() = project.service<CodeConfigService>().cfg().ai

    private fun isEnabled(): Boolean {
        val cfg = cfg()
        return cfg.enabled && cfg.endpoint.isNotBlank() && cfg.api_key_env.isNotBlank()
    }

    private fun apiKey(): String? {
        val envName = cfg().api_key_env
        return System.getenv(envName)
    }

    /**
     * Сгенерировать описание PR на основе краткого описания diff’а и информации о покрытии.
     * В реальной жизни сюда можно передавать больше контекста.
     */
    fun suggestPrDescription(diffSummary: String, coverageInfo: String): String {
        val disabledMsg = "AI-помощник отключён или не сконфигурирован. " +
                "Проверьте секцию ai в CODE.yaml и переменную окружения с API-ключом."
        if (!isEnabled()) {
            lastPrDescription = disabledMsg
            return disabledMsg
        }

        val key = apiKey() ?: run {
            val msg = "$disabledMsg (API ключ не найден в окружении)"
            lastPrDescription = msg
            return msg
        }

        return try {
            val prompt = buildPrPrompt(diffSummary, coverageInfo)
            val result = callAiApi(prompt, key)
            lastPrDescription = result
            result
        } catch (e: Exception) {
            log.warn("AI PR description request failed", e)
            val msg = "Не удалось получить ответ от AI-помощника: ${e.message}"
            lastPrDescription = msg
            msg
        }
    }

    fun suggestTests(changesSummary: String, uncoveredAreas: List<String>): String {
        val disabledMsg = "AI-помощник отключён или не сконфигурирован."
        if (!isEnabled()) {
            lastTestsSuggestion = disabledMsg
            return disabledMsg
        }

        val key = apiKey() ?: run {
            val msg = "$disabledMsg (API ключ не найден в окружении)"
            lastTestsSuggestion = msg
            return msg
        }

        return try {
            val prompt = buildTestsPrompt(changesSummary, uncoveredAreas)
            val result = callAiApi(prompt, key)
            lastTestsSuggestion = result
            result
        } catch (e: Exception) {
            log.warn("AI tests suggestion request failed", e)
            val msg = "Не удалось получить ответ от AI-помощника: ${e.message}"
            lastTestsSuggestion = msg
            msg
        }
    }

    /** Простейший формат промпта — одна строка текста, дальше модель сама */
    private fun buildPrPrompt(diffSummary: String, coverageInfo: String): String =
        """
        Ты — помощник ревьюера кода.
        На основе краткого описания изменений и информации о покрытии тестами
        сформируй черновик описания Pull Request и рекомендацию по нему.

        Также предложи название коммита в формате:
        ${'$'}{issue}-${'$'}{slug}: Краткое название коммита
        Например: AND-123-login: Обработка ошибок авторизации

        Описание изменений:
        $diffSummary

        Информация о покрытии:
        $coverageInfo

        Структурируй ответ строго в следующем виде:

        Commit title:
        ISSUE-SLUG: Краткое название коммита

        Summary:
        - ...

        Motivation:
        - ...

        Changes:
        - ...

        Testing:
        - ...

        Risks:
        - ...

        Recommendation:
        - Короткая рекомендация по PR (например, "готов к ревью", "нужно разбить на несколько PR", "следует добавить тесты на ...").
        """.trimIndent()

    // --- промпт для тестов: сценарий + реализация ---
    private fun buildTestsPrompt(changesSummary: String, uncoveredAreas: List<String>): String =
        """
        Ты — помощник по тестированию в проекте на Kotlin (JUnit5).
        На основе описания изменений и списка областей, которые остались без покрытия,
        предложи список тестовых сценариев.

        Для КАЖДОГО сценария:
        1) Сформулируй его кратко (1–2 строки).
        2) Приведи пример реализации теста на Kotlin + JUnit5.
           - Укажи имя тестового метода.
           - Покажи полный код теста (импортов можно не указывать, если они стандартные).
           - Если нужно замокировать зависимости, используй простой псевдо-код или Mockito.

        Описание изменений:
        $changesSummary

        Области без покрытия:
        ${uncoveredAreas.joinToString(separator = "\n- ", prefix = "- ")}

        Формат ответа:
        1) Сценарий: ...
           Пример теста:
           ```kotlin
           @Test
           fun `...`() {
               // ...
           }
           ```
        2) Сценарий: ...
           Пример теста:
           ```kotlin
           ...
           ```
        """.trimIndent()

    /**
     * Универсальный метод вызова LLM API.
     * Здесь используется обобщённый JSON-формат (упрощённо).
     * Ты можешь адаптировать под конкретный провайдер (OpenAI, внутренний сервис и т.п.).
     */
    private fun callAiApi(prompt: String, apiKey: String): String {
        val cfg = cfg()
        val endpoint = cfg.endpoint
        val model = cfg.model.ifBlank { "default-model" }

        // Пример простого JSON-запроса в стиле chat/completions
        val payload = JSONObject()
            .put("model", model)
            .put("messages", listOf(
                JSONObject().put("role", "system").put("content", "Ты помогаешь разработчикам улучшать качество кода."),
                JSONObject().put("role", "user").put("content", prompt)
            ))

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("AI API returned status ${response.statusCode()}: ${response.body()}")
        }

        val body = response.body()

        // Сюда можно поставить реальный парсинг по схеме конкретного API.
        // Пока предполагаем, что body уже содержит текст ответа или поле "content".
        return try {
            val json = JSONObject(body)
            // Примерно в стиле OpenAI: choices[0].message.content
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val first = choices.getJSONObject(0)
                val msg = first.optJSONObject("message")
                msg?.optString("content") ?: body
            } else {
                body
            }
        } catch (_: Exception) {
            body
        }
    }

    companion object {
        fun getInstance(project: Project): AiAssistantService = project.service()
    }
}
