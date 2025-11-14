package ru.codeplugin.inspections

import com.intellij.codeInspection.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import ru.codeplugin.services.CodeConfigService

class NotFormattedInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Файл не отформатирован (CODE)"
    override fun getShortName(): String = "CodeNotFormatted"
    override fun getGroupDisplayName(): String = "CODE"
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                // ⛔ Если в CODE.yaml выключена проверка стиля – инспекция молчит
                val cfg = file.project.getService(CodeConfigService::class.java).cfg()
                if (!cfg.develop.require_code_style_check) return

                if (file.virtualFile == null || file.textLength == 0) return

                val project = file.project
                val copy = file.copy() as PsiFile
                try {
                    CodeStyleManager.getInstance(project).reformat(copy)
                } catch (_: Throwable) {
                    return // если форматтер для языка не поддержан – тихо выходим
                }

                if (copy.text != file.text) {
                    holder.registerProblem(
                        file,
                        "Файл отклоняется от форматирования Code Style (CODE)",
                        ProblemHighlightType.WEAK_WARNING,
                        ReformatQuickFix()
                    )
                }
            }
        }
}

class ReformatQuickFix : LocalQuickFix {
    override fun getFamilyName(): String = "Отформатировать файл (CODE)"
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement.containingFile ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }
    }
}
