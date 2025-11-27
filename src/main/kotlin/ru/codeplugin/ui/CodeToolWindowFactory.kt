package ru.codeplugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CodeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val codeTW = CodeToolWindow(project)
        project.putUserData(KEY, codeTW)

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(codeTW.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private val KEY = Key.create<CodeToolWindow>("CODE_TOOL_WINDOW")

fun getInstance(project: Project): CodeToolWindow? =
    project.getUserData(KEY)