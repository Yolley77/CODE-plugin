package ru.codeplugin.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import ru.codeplugin.services.CodeConfigService
import javax.swing.JButton
import javax.swing.JComponent
import java.awt.GridLayout

class CodeToolWindow(private val project: Project) {
    val component: JComponent by lazy { build() }

    private fun build(): JComponent {
        val cfg = project.service<CodeConfigService>().cfg()
        val panel = JBPanel<JBPanel<*>>(GridLayout(0, 1, 8, 8))

        panel.add(JBLabel("CODE — минимальный каркас"))
        panel.add(JBLabel("Шаги: Prepare → Develop → Control → Apply"))

        panel.add(JBLabel("Шаблон ветки: ${cfg.prepare.branch_format}"))
        panel.add(JBLabel("KDoc обязателен: ${cfg.develop.comments_required_for_new_methods}"))
        panel.add(JBLabel("Мин. покрытие (общ.): ${cfg.control.coverage.min_overall}"))
        panel.add(JBLabel("Мин. ревьюеров: ${cfg.apply.pr.reviewers_min}"))

        val btnReload = JButton("Перечитать CODE.yaml")
        btnReload.addActionListener {
            project.service<CodeConfigService>().reload()
            // На простоте: перезапустить окно — достаточно закрыть/открыть заново.
            btnReload.text = "Готово ✓ (обновите окно)"
        }
        panel.add(btnReload)

        return panel
    }
}
