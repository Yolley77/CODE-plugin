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

    // ----- промпты (как мы уже согласовали) -----

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
}
