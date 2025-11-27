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
import com.intellij.openapi.application.ApplicationManager
import ru.codeplugin.ui.getInstance

class ControlAiSuggestTestsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ai = project.service<AiAssistantService>()
        val group = NotificationGroupManager.getInstance().getNotificationGroup("CODE")

        ai.isTestsLoading = true
        ai.setLastTestsSuggestion("Запрос к AI выполняется...")

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "CODE AI: Test Suggestions", false) {

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Анализируем изменения..."

                val changeListManager = ChangeListManager.getInstance(project)
                val defaultList = changeListManager.defaultChangeList
                val files = defaultList.changes.mapNotNull { it.virtualFile?.path }.distinct()

                val changesSummary = if (files.isEmpty()) {
                    "Нет незакоммиченных изменений; анализ основан только на общем описании изменений в PR."
                } else {
                    buildString {
                        appendLine("Изменённые файлы (${files.size}):")
                        files.forEach { appendLine("- $it") }
                    }
                }

                // пока используем список файлов как proxy «областей без покрытия»
                val uncoveredAreas = files.ifEmpty { emptyList() }

                indicator.text = "Отправляем запрос к AI..."

                ai.suggestTests(changesSummary, uncoveredAreas)

                ai.isTestsLoading = false

                group.createNotification(
                    "CODE / CONTROL / AI",
                    "AI-подсказки по тестам обновлены в окне CODE.\n" +
                            "Откройте ToolWindow CODE, чтобы скопировать сценарии и примеры тестов.",
                    NotificationType.INFORMATION
                ).notify(project)

                ApplicationManager.getApplication().invokeLater {
                    getInstance(project)?.refresh()
                }
            }
        })
    }
}
