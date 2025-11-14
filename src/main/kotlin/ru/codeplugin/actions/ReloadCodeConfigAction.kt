package ru.codeplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import ru.codeplugin.services.CodeConfigService

class ReloadCodeConfigAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<CodeConfigService>().reload()

        NotificationGroupManager.getInstance()
            .getNotificationGroup("CODE")
            .createNotification(
                "CODE: конфигурация перечитана",
                NotificationType.INFORMATION
            ).notify(project)
    }
}
