package ru.codeplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.vcs.changes.ChangeListManager
import ru.codeplugin.services.CodeConfigService

class ApplyValidateChangesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cfg = project.service<CodeConfigService>().cfg()
        val max = cfg.apply.max_files_changed

        val changeListManager = ChangeListManager.getInstance(project)
        val defaultList = changeListManager.defaultChangeList
        val changes = defaultList.changes

        // Считаем количество уникальных файлов
        val filesCount = changes.mapNotNull { it.virtualFile }.distinct().size

        val group = NotificationGroupManager.getInstance().getNotificationGroup("CODE")

        if (filesCount <= max) {
            group.createNotification(
                "CODE / APPLY",
                "OK: количество изменённых файлов ($filesCount) не превышает лимит $max.\n" +
                        "Создание PR/MR соответствует этапу APPLY.",
                NotificationType.INFORMATION
            ).notify(project)
        } else {
            group.createNotification(
                "CODE / APPLY",
                "Проблема: в default change list ${filesCount} изменённых файлов, лимит по CODE.yaml — $max.\n" +
                        "Рекомендуется разбить изменения на несколько меньших PR/MR.",
                NotificationType.WARNING
            ).notify(project)
        }
    }
}
