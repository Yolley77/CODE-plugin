package ru.codeplugin.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import ru.codeplugin.services.CodeConfigService
import java.awt.GridLayout
import javax.swing.JComponent

class CodeToolWindow(private val project: Project) {
    val component: JComponent by lazy { build() }

    private fun build(): JComponent {
        val cfg = project.service<CodeConfigService>().cfg()
        val panel = JBPanel<JBPanel<*>>(GridLayout(0, 1, 4, 4))

        panel.add(JBLabel("CODE Methodology — обзор конфигурации"))
        panel.add(JBLabel("Этапы: PREPARE → DEVELOP → CONTROL → APPLY"))
        panel.add(JBLabel(" "))

        // PREPARE
        panel.add(JBLabel("PREPARE"))
        panel.add(JBLabel("  branch_format: ${cfg.prepare.branch_format}"))
        panel.add(JBLabel(" "))

        // DEVELOP
        panel.add(JBLabel("DEVELOP"))
        panel.add(JBLabel("  require_code_style_check: ${cfg.develop.require_code_style_check}"))
        panel.add(JBLabel(" "))

        // CONTROL
        panel.add(JBLabel("CONTROL"))
        panel.add(JBLabel("  coverage.min_overall: ${cfg.control.coverage.min_overall}"))
        panel.add(JBLabel(" "))

        // APPLY
        panel.add(JBLabel("APPLY"))
        panel.add(JBLabel("  max_files_changed: ${cfg.apply.max_files_changed}"))

        panel.add(JBLabel(" "))
        panel.add(JBLabel("Кнопка CODE в верхнем Toolbar запускает действия каждого этапа."))

        return panel
    }
}
