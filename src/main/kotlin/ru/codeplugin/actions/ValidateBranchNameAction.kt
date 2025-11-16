package ru.codeplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import git4idea.GitUtil
import ru.codeplugin.services.CodeBranchLifecycleService
import ru.codeplugin.services.CodeConfigService

class ValidateBranchNameAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cfg = project.service<CodeConfigService>().cfg()
        val pattern = cfg.prepare.branch_format
            .replace("\${issue}", "[A-Z]+-\\d+")
            .replace("\${slug}", "[a-z0-9]+(?:-[a-z0-9]+)*")
            .toRegex()

        val branch = currentBranch(project)
        val group = NotificationGroupManager.getInstance().getNotificationGroup("CODE")

        if (branch == null) {
            group.createNotification(
                "CODE / PREPARE",
                "Git-репозиторий не найден или ветка не определена.",
                NotificationType.ERROR
            ).notify(project)
            return
        }

        if (pattern.matches(branch)) {
            // ✅ Ветка ок — фиксируем начало «жизни» через CODE
            CodeBranchLifecycleService.getInstance(project).markBranchSeen(branch)

            group.createNotification(
                "CODE / PREPARE",
                "OK: текущая ветка '$branch' соответствует шаблону ${cfg.prepare.branch_format}",
                NotificationType.INFORMATION
            ).notify(project)
        } else {
            group.createNotification(
                "CODE / PREPARE",
                "Проблема: ветка '$branch' не соответствует шаблону ${cfg.prepare.branch_format}",
                NotificationType.WARNING
            ).notify(project)
        }
    }

    private fun currentBranch(project: Project): String? =
        GitUtil.getRepositories(project).firstOrNull()?.currentBranchName
}
