package ru.codeplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import ru.codeplugin.services.CodeConfigService
import ru.codeplugin.ui.getInstance

class ReloadCodeConfigAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<CodeConfigService>().reload()

        ApplicationManager.getApplication().invokeLater {
            getInstance(project)?.refresh()
        }

        NotificationGroupManager.getInstance()
            .getNotificationGroup("CODE")
            .createNotification(
                "CODE: конфигурация перечитана",
                NotificationType.INFORMATION
            ).notify(project)
    }
}
