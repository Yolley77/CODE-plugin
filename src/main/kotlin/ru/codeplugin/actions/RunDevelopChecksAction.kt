package ru.codeplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.psi.codeStyle.CodeStyleManager
import ru.codeplugin.services.CodeConfigService

class RunDevelopChecksAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cfg = project.service<CodeConfigService>().cfg()
        if (!cfg.develop.require_code_style_check) {
            Messages.showInfoMessage(
                project,
                "В CODE.yaml develop.require_code_style_check=false.\nПроверка стиля CODE выключена.",
                "CODE: DEVELOP"
            )
            return
        }

        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        if (psiFile == null) {
            Messages.showWarningDialog(
                project,
                "Откройте файл, который нужно проверить/отформатировать, и повторите.",
                "CODE: DEVELOP"
            )
            return
        }

        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(psiFile)
        }

        Messages.showInfoMessage(
            project,
            "Файл отформатирован в соответствии с Code Style.\nИнспекция CODE будет отслеживать отклонения.",
            "CODE: DEVELOP"
        )
    }
}

