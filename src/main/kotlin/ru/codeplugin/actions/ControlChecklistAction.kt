package ru.codeplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import ru.codeplugin.services.CodeConfigService

class ControlChecklistAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cfg = project.service<CodeConfigService>().cfg()
        val min = cfg.control.coverage.min_overall

        val message = """
      Этап CONTROL (покрытие тестами)
      
      Целевой порог покрытия из CODE.yaml: ${"%.0f".format(min * 100)}%
      
      Как проверить через стандартный Run with Coverage:
      1. Выберите конфигурацию запуска (Run Configuration), которая гоняет основные тесты.
      2. Запустите её через Run with Coverage:
         - в контекстном меню конфигурации выберите "Run '…' with Coverage"
         - или нажмите иконку ▶ с зелёным щитом.
      3. Откройте окно "Coverage":
         - View → Tool Windows → Coverage
         - убедитесь, что выбран последний прогон.
      4. Посмотрите суммарное покрытие по модулю/пакету:
         - сравните значение с порогом из CODE.yaml (${min * 100}%).
      5. Если покрытие ниже порога:
         - допишите/уточните юнит-тесты для изменённых участков кода.
         - повторите Run with Coverage.
      
      Это чек-лист, который обеспечивает соответствие изменений этапу CONTROL.
    """.trimIndent()

        Messages.showInfoMessage(project, message, "CODE: CONTROL Checklist")
    }
}
