package ru.codeplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import ru.codeplugin.services.CodeConfigService
import ru.codeplugin.services.JacocoCoverageReader
import java.nio.file.Path
import java.nio.file.Paths

class ControlCheckCoverageAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cfg = project.service<CodeConfigService>().cfg()
        val group = NotificationGroupManager.getInstance().getNotificationGroup("CODE")

        val basePath = project.basePath
        if (basePath == null) {
            group.createNotification(
                "CODE / CONTROL",
                "Не удалось определить корень проекта для поиска отчёта покрытия.",
                NotificationType.ERROR
            ).notify(project)
            return
        }

        val reportRelative = cfg.control.coverage.report_path
        val reportPath: Path = Paths.get(basePath, reportRelative)

        val coverage = JacocoCoverageReader.readCoverage(reportPath)
        if (coverage == null) {
            group.createNotification(
                "CODE / CONTROL",
                "Отчёт покрытия не найден или не распознан по пути:\n$reportPath\n" +
                        "Убедитесь, что Jacoco сгенерировал XML, и путь указан в CODE.yaml.",
                NotificationType.WARNING
            ).notify(project)
            return
        }

        val min = cfg.control.coverage.min_overall
        val coveragePercent = coverage * 100.0
        val minPercent = min * 100.0

        val formatted = "%.1f".format(coveragePercent)
        val formattedMin = "%.1f".format(minPercent)

        val (type, message) =
            if (coverage >= min) {
                NotificationType.INFORMATION to
                        "OK: суммарное покрытие $formatted% ≥ порога $formattedMin%.\n" +
                        "Этап CONTROL по покрытию тестами выполняется."
            } else {
                NotificationType.WARNING to
                        "Проблема: суммарное покрытие $formatted% < порога $formattedMin%.\n" +
                        "Рекомендуется добавить тесты и повторить проверку."
            }

        group.createNotification("CODE / CONTROL", message, type).notify(project)
    }
}
