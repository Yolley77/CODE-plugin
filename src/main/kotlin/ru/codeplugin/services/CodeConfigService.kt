package ru.codeplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class CodeConfigService(private val project: Project) {

    @Volatile private var config: CodeConfig = CodeConfig() // дефолт

    init { reload() }

    fun reload() {
        val root = project.basePath ?: return
        val path: Path = Paths.get(root, "CODE.yaml")
        config = if (Files.exists(path)) {
            Files.newBufferedReader(path).use { reader ->
                Yaml().loadAs(reader, CodeConfig::class.java) ?: CodeConfig()
            }
        } else CodeConfig()
    }

    fun cfg(): CodeConfig = config

    companion object {
        fun getInstance(project: Project): CodeConfigService = project.service()
    }
}

/** Минимальная модель конфигурации — только то, что используем прямо сейчас */
data class CodeConfig(
    val version: Int = 1,
    val prepare: Prepare = Prepare(),
    val develop: Develop = Develop(),
    val control: Control = Control(),
    val apply: Apply = Apply()
) {
    data class Prepare(val branch_format: String = "feature/\${issue}-\${slug}")
    data class Develop(val comments_required_for_new_methods: Boolean = true)
    data class Control(val coverage: Coverage = Coverage())
    data class Apply(val pr: Pr = Pr())
    data class Coverage(val min_overall: Double = 0.8)
    data class Pr(val reviewers_min: Int = 2)
}
