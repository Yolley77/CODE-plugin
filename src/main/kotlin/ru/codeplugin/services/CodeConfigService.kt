package ru.codeplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class CodeConfigService(private val project: Project) {

    @Volatile private var config: CodeConfig = CodeConfig()

    init { reload() }

    fun reload() {
        val root = project.basePath ?: run { config = CodeConfig(); return }
        val path: Path = Paths.get(root, "CODE.yaml")
        config = if (Files.exists(path)) {
            try {
                Files.newBufferedReader(path).use { reader ->
                    Yaml().loadAs(reader, CodeConfig::class.java) ?: CodeConfig()
                }
            } catch (ex: Exception) {
                // Покажем понятное уведомление и вернёмся к дефолтам
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("CODE")
                    .createNotification(
                        "CODE: ошибка чтения CODE.yaml",
                        "Использую значения по умолчанию. Детали: ${ex.message}",
                        NotificationType.WARNING
                    ).notify(project)
                CodeConfig()
            }
        } else {
            CodeConfig()
        }
    }

    fun cfg(): CodeConfig = config

    companion object { fun getInstance(project: Project): CodeConfigService = project.service() }
}

/** ===== JavaBean-модель: пустые конструкторы + var-свойства ===== */

class CodeConfig() {
    var version: Int = 1
    var prepare: Prepare = Prepare()
    var develop: Develop = Develop()
    var control: Control = Control()
    var apply: Apply = Apply()
    var ai: Ai = Ai()
}

class Ai() {
    var enabled: Boolean = false
    var endpoint: String = ""
    var model: String = ""
    var api_key_env: String = ""
}

class Prepare() {
    var branch_format: String = "feature/\${issue}-\${slug}"
    var max_branch_age_hours: Int = 24
}

class Develop() {
    var require_code_style_check: Boolean = true
}

class Control() {
    var coverage: Coverage = Coverage()
}

class Coverage() {
    var min_overall: Double = 0.8
    var report_path: String = "build/reports/jacoco/test/jacocoTestReport.xml"
}

class Apply() {
    var max_files_changed: Int = 30
}
