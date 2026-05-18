package ru.codeplugin.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import ru.codeplugin.services.AiAssistantService
import ru.codeplugin.services.CodeMdService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ManageCodeMdAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = project.basePath ?: return

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "CODE.md: генерация или проверка", false) {

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Анализ проекта для запроса к LLM..."
                val codeMdPath = Paths.get(root, "CODE.md")
                val projectRoot = Paths.get(root)

                try {
                    if (Files.exists(codeMdPath)) {
                        validateExisting(project, projectRoot, codeMdPath, indicator)
                    } else {
                        createStarter(project, projectRoot, codeMdPath, indicator)
                    }
                } catch (ex: Exception) {
                    notify(
                        project,
                        "CODE.md",
                        "Не удалось обработать CODE.md: ${ex.message ?: ex.javaClass.simpleName}",
                        NotificationType.ERROR
                    )
                }
            }
        })
    }

    private fun validateExisting(
        project: Project,
        projectRoot: Path,
        codeMdPath: Path,
        indicator: ProgressIndicator
    ) {
        val ai = project.service<AiAssistantService>()
        if (!ai.isConfigured()) {
            notifyAiDisabled(project)
            return
        }

        indicator.text = "Отправляем CODE.md и контекст проекта в LLM..."
        val content = Files.readString(codeMdPath, StandardCharsets.UTF_8)
        val projectContext = CodeMdService.buildProjectContext(projectRoot)
        val message = ai.reviewCodeMd(projectContext, content)

        notify(
            project,
            "CODE.md / LLM VALIDATE",
            message,
            NotificationType.INFORMATION
        )
    }

    private fun createStarter(
        project: Project,
        projectRoot: Path,
        codeMdPath: Path,
        indicator: ProgressIndicator
    ) {
        val ai = project.service<AiAssistantService>()
        if (!ai.isConfigured()) {
            notifyAiDisabled(project)
            return
        }

        indicator.text = "Отправляем контекст проекта в LLM для генерации CODE.md..."
        val projectContext = CodeMdService.buildProjectContext(projectRoot)
        val generated = CodeMdService.normalizeGeneratedMarkdown(ai.generateCodeMd(projectContext))

        if (generated.startsWith("AI-ассистент") || generated.startsWith("Не удалось")) {
            notify(project, "CODE.md / LLM", generated, NotificationType.ERROR)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project, "Create CODE.md", null, Runnable {
                if (Files.exists(codeMdPath)) {
                    notify(
                        project,
                        "CODE.md / PREPARE",
                        "CODE.md появился до завершения генерации. Существующий файл не перезаписан.",
                        NotificationType.WARNING
                    )
                    return@Runnable
                }

                Files.writeString(codeMdPath, generated, StandardCharsets.UTF_8)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(codeMdPath)

                notify(
                    project,
                    "CODE.md / PREPARE",
                    "Создан CODE.md на основе анализа проекта и цикла Prepare, Develop, Control, Apply.",
                    NotificationType.INFORMATION
                )
            })
        }
    }

    private fun notifyAiDisabled(project: Project) {
        notify(
            project,
            "CODE.md / LLM",
            "Генерация и проверка CODE.md выполняются через LLM. Включите и настройте секцию ai в CODE.yaml.",
            NotificationType.WARNING
        )
    }

    private fun notify(project: Project, title: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CODE")
            .createNotification(title, message, type)
            .notify(project)
    }
}
