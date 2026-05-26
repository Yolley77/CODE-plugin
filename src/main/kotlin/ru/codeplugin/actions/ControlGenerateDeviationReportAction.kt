package ru.codeplugin.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import ru.codeplugin.services.DeviationAssessmentService
import java.util.Locale

class ControlGenerateDeviationReportAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "CODE: Deviation Assessment", false) {

            override fun run(indicator: ProgressIndicator) {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("CODE")

                try {
                    indicator.text = "Считаем метрики CODE..."
                    val service = DeviationAssessmentService.getInstance(project)
                    val report = service.assess()

                    indicator.text = "Записываем Markdown-отчёт..."
                    val reportPath = service.writeMarkdownReport(report)
                    val deviation = report.integralDeviation?.let {
                        String.format(Locale.US, "%.3f", it)
                    } ?: "unknown"
                    val worstStage = report.worstStage?.label ?: "unknown"

                    ApplicationManager.getApplication().invokeLater {
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(reportPath)
                        group.createNotification(
                            "CODE / Deviation Report",
                            "Сформирован отчёт количественной оценки отклонений.\n" +
                                "F=$deviation, проблемный этап: $worstStage.\n" +
                                "Файл: $reportPath",
                            NotificationType.INFORMATION
                        ).notify(project)
                    }
                } catch (ex: Exception) {
                    group.createNotification(
                        "CODE / Deviation Report",
                        "Не удалось сформировать отчёт отклонений: ${ex.message ?: ex.javaClass.simpleName}",
                        NotificationType.ERROR
                    ).notify(project)
                }
            }
        })
    }
}
