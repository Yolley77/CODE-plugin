package ru.codeplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import git4idea.GitUtil
import ru.codeplugin.services.CodeConfigService

class ValidateBranchNameAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cfg = project.service<CodeConfigService>().cfg()
        val expected = cfg.prepare.branch_format
            .replace("\${issue}", "[A-Z]+-\\d+")
            .replace("\${slug}", "[a-z0-9]+(?:-[a-z0-9]+)*")
            .toRegex()

        val branch = currentBranch(project)
        val (msg, type) = if (branch == null) {
            "Git-репозиторий не найден или ветка не определена" to NotificationType.ERROR
        } else if (expected.matches(branch)) {
            "OK: текущая ветка '$branch' соответствует шаблону branch_format" to NotificationType.INFORMATION
        } else {
            "Проблема: ветка '$branch' не соответствует шаблону: ${cfg.prepare.branch_format}" to NotificationType.WARNING
        }

        NotificationGroupManager.getInstance().getNotificationGroup("CODE")
            .createNotification("CODE / Prepare", msg, type)
            .notify(project)
    }

    private fun currentBranch(project: Project): String? {
        val repos = GitUtil.getRepositories(project)
        if (repos.isEmpty()) return null
        return repos.first().currentBranchName
    }

}
