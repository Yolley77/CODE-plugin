package ru.codeplugin.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vcs.changes.ChangeListManager
import ru.codeplugin.services.AiAssistantService
import ru.codeplugin.services.CodeConfigService
import ru.codeplugin.services.JacocoCoverageReader
import java.nio.file.Paths

class ApplyAiSuggestPrDescriptionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ai = project.service<AiAssistantService>()
        val cfg = project.service<CodeConfigService>().cfg()
        val group = NotificationGroupManager.getInstance().getNotificationGroup("CODE")

        ai.isPrLoading = true
        ai.setLastPrDescription("Запрос к AI выполняется...")

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "CODE AI: PR Description", false) {

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Формируем сводку изменений..."

                val changeListManager = ChangeListManager.getInstance(project)
                val defaultList = changeListManager.defaultChangeList
                val files = defaultList.changes.mapNotNull { it.virtualFile?.path }.distinct()

                val diffSummary = if (files.isEmpty()) {
                    "Нет незакоммиченных изменений. PR может описывать уже закоммиченный diff."
                } else {
                    buildString {
                        appendLine("Изменённые файлы (${files.size}):")
                        files.forEach { appendLine("- $it") }
                    }
                }

                val basePath = project.basePath
                val coverageInfo = if (basePath != null) {
                    val coverageCfg = cfg.control.coverage
                    val path = Paths.get(basePath, coverageCfg.report_path)
                    val coverage = JacocoCoverageReader.readCoverage(path)
                    if (coverage != null) {
                        val actual = "%.1f".format(coverage * 100.0)
                        val min = "%.1f".format(coverageCfg.min_overall * 100.0)
                        "Фактическое покрытие: $actual%, порог CODE: $min%."
                    } else {
                        "Отчёт покрытия не найден или не распознан по пути: $path."
                    }
                } else {
                    "Корень проекта не определён; информация о покрытии недоступна."
                }

                indicator.text = "Отправляем запрос к AI..."

                ai.suggestPrDescription(diffSummary, coverageInfo)

                ai.isPrLoading = false

                group.createNotification(
                    "CODE / APPLY / AI",
                    "AI-сгенерированное описание PR обновлено в окне CODE.\n" +
                            "Откройте ToolWindow CODE, чтобы скопировать commit title и текст.",
                    NotificationType.INFORMATION
                ).notify(project)
            }
        })
    }
}
