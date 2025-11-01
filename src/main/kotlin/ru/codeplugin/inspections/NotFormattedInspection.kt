package ru.codeplugin.inspections

import com.intellij.codeInspection.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager

/** Язык-агностичная проверка: совпадает ли текущий файл с результатом форматтера. */
class NotFormattedInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "CODE: файл не отформатирован по Code Style"
    override fun getShortName(): String = "CodeNotFormatted"
    override fun getGroupDisplayName(): String = "CODE"
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file.virtualFile == null || file.textLength == 0) return

                // Скопируем PSI и отформатируем его
                val project = file.project
                val copy = file.copy() as PsiFile
                try {
                    CodeStyleManager.getInstance(project).reformat(copy)
                } catch (_: Throwable) {
                    return // если форматтер для языка не поддержан — тихо выходим
                }

                // Сравним тексты
                val formatted = copy.text
                if (formatted != file.text) {
                    holder.registerProblem(
                        file,
                        "Файл отклоняется от форматирования Code Style",
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
