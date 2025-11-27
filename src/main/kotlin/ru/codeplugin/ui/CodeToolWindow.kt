package ru.codeplugin.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import ru.codeplugin.services.AiAssistantService
import ru.codeplugin.services.CodeConfigService
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

class CodeToolWindow(private val project: Project) {

    private val prText = JTextArea()
    private val testsText = JTextArea()

    val component: JComponent by lazy { build() }

    private fun build(): JComponent {
        val cfg = project.service<CodeConfigService>().cfg()

        val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())

        // --- верх: конфигурация CODE ---
        val infoPanel = JBPanel<JBPanel<*>>(GridLayout(0, 1, 4, 4))
        infoPanel.add(JBLabel("CODE Methodology — обзор конфигурации"))
        infoPanel.add(JBLabel("Этапы: PREPARE → DEVELOP → CONTROL → APPLY"))
        infoPanel.add(JBLabel(" "))

        infoPanel.add(JBLabel("PREPARE"))
        infoPanel.add(JBLabel("  branch_format: ${cfg.prepare.branch_format}"))
        infoPanel.add(JBLabel("  max_branch_age_hours: ${cfg.prepare.max_branch_age_hours}"))
        infoPanel.add(JBLabel(" "))

        infoPanel.add(JBLabel("DEVELOP"))
        infoPanel.add(JBLabel("  require_code_style_check: ${cfg.develop.require_code_style_check}"))
        infoPanel.add(JBLabel(" "))

        infoPanel.add(JBLabel("CONTROL"))
        infoPanel.add(JBLabel("  coverage.min_overall: ${cfg.control.coverage.min_overall}"))
        infoPanel.add(JBLabel("  coverage.report_path: ${cfg.control.coverage.report_path}"))
        infoPanel.add(JBLabel(" "))

        infoPanel.add(JBLabel("APPLY"))
        infoPanel.add(JBLabel("  max_files_changed: ${cfg.apply.max_files_changed}"))
        infoPanel.add(JBLabel(" "))

        infoPanel.add(JBLabel("Используйте кнопку CODE в верхнем Toolbar для запуска этапов и AI-функций."))

        mainPanel.add(infoPanel, BorderLayout.NORTH)

        // --- центр: ответы AI ---
        prText.isEditable = false
        prText.lineWrap = true
        prText.wrapStyleWord = true

        testsText.isEditable = false
        testsText.lineWrap = true
        testsText.wrapStyleWord = true

        val prScroll = JBScrollPane(prText)
        val testsScroll = JBScrollPane(testsText)
        prScroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        testsScroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED

        val prPanel = JBPanel<JBPanel<*>>(BorderLayout())
        prPanel.add(JBLabel("Последний ответ AI по PR (описание + commit title):"), BorderLayout.NORTH)
        prPanel.add(prScroll, BorderLayout.CENTER)

        val testsPanel = JBPanel<JBPanel<*>>(BorderLayout())
        testsPanel.add(JBLabel("Последний ответ AI по тестам (с примерами реализации):"), BorderLayout.NORTH)
        testsPanel.add(testsScroll, BorderLayout.CENTER)

        val aiPanel = JBPanel<JBPanel<*>>(GridLayout(2, 1, 4, 4))
        aiPanel.add(prPanel)
        aiPanel.add(testsPanel)

        mainPanel.add(aiPanel, BorderLayout.CENTER)

        // первичная инициализация
        updateAiTexts()

        return mainPanel
    }

    fun refresh() {
        updateAiTexts()
    }

    private fun updateAiTexts() {
        val aiService = project.service<AiAssistantService>()

        prText.text = when {
            aiService.isPrLoading ->
                "Запрос к AI-помощнику для описания PR выполняется..."
            aiService.getLastPrDescription() != null ->
                aiService.getLastPrDescription()
            else ->
                "AI-описание PR пока не получено.\n" +
                        "Используйте действие 'Apply: AI Suggest PR Description' в меню CODE."
        }

        testsText.text = when {
            aiService.isTestsLoading ->
                "Запрос к AI-помощнику для предложений по тестам выполняется..."
            aiService.getLastTestsSuggestion() != null ->
                aiService.getLastTestsSuggestion()
            else ->
                "AI-подсказки по тестам пока не получены.\n" +
                        "Используйте действие 'Control: AI Suggest Tests' в меню CODE."
        }
    }
}
