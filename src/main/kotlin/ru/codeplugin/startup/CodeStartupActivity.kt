package ru.codeplugin.startup

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import ru.codeplugin.services.CodeConfigService
import java.nio.file.Files
import java.nio.file.Paths

class CodeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val root = project.basePath ?: return
        val hasConfig = Files.exists(Paths.get(root, "CODE.yaml"))
        if (!hasConfig) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CODE")
                .createNotification(
                    "CODE: в проекте нет CODE.yaml",
                    "Создайте CODE.yaml в корне — плагин применит дефолтные правила до тех пор.",
                    NotificationType.WARNING
                ).notify(project)
        } else {
            CodeConfigService.getInstance(project).reload()
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CODE")
                .createNotification("CODE: конфигурация загружена", NotificationType.INFORMATION)
                .notify(project)
        }
    }
}
