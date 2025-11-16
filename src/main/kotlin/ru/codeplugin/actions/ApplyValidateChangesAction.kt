package ru.codeplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import git4idea.GitUtil
import ru.codeplugin.services.CodeBranchLifecycleService
import ru.codeplugin.services.CodeConfigService

class ApplyValidateChangesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cfg = project.service<CodeConfigService>().cfg()
        val group = NotificationGroupManager.getInstance().getNotificationGroup("CODE")

        val branch = currentBranch(project)
        if (branch == null) {
            group.createNotification(
                "CODE / APPLY",
                "Невозможно оценить APPLY: не найдена текущая ветка Git.",
                NotificationType.ERROR
            ).notify(project)
            return
        }

        // 1) Число изменённых файлов
        val changeListManager = ChangeListManager.getInstance(project)
        val defaultList = changeListManager.defaultChangeList
        val filesCount = defaultList.changes.mapNotNull { it.virtualFile }.distinct().size

        val maxFiles = cfg.apply.max_files_changed
        val filesOk = filesCount <= maxFiles

        // 2) Возраст ветки
        val branchLifeService = CodeBranchLifecycleService.getInstance(project)
        val ageHours = branchLifeService.getBranchAgeHours(branch)
        val maxAgeHours = cfg.prepare.max_branch_age_hours

        val ageInfo = if (ageHours == null) {
            "Возраст ветки для CODE пока неизвестен (ветка не проходила PREPARE)."
        } else {
            "Возраст ветки: %.1f ч, допустимый порог: %d ч."
                .format(ageHours, maxAgeHours)
        }

        val ageOk = ageHours == null || ageHours <= maxAgeHours

        val statusLines = buildString {
            appendLine("Ветка: $branch")
            appendLine("Изменённых файлов: $filesCount (лимит CODE.yaml: $maxFiles)")
            appendLine(ageInfo)
        }

        val type = when {
            filesOk && ageOk -> NotificationType.INFORMATION
            else -> NotificationType.WARNING
        }

        val summary = when {
            filesOk && ageOk ->
                "OK: изменения соответствуют лимиту файлов и возраст ветки в пределах нормы."
            filesOk && !ageOk ->
                "Файлы в норме, но ветка живёт дольше, чем допускает CODE."
            !filesOk && ageOk ->
                "Возраст ветки в норме, но изменений слишком много."
            else ->
                "И количество файлов, и возраст ветки выходят за рамки CODE."
        }

        group.createNotification(
            "CODE / APPLY",
            "$summary\n\n$statusLines",
            type
        ).notify(project)
    }

    private fun currentBranch(project: Project): String? =
        GitUtil.getRepositories(project).firstOrNull()?.currentBranchName
}
