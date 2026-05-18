package ru.codeplugin.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object CodeMdService {
    private const val MAX_LIST_ITEMS = 12
    private const val MAX_WALK_DEPTH = 8
    private const val MAX_PROJECT_FILES = 1_000

    private val requiredSections = listOf("Prepare", "Develop", "Control", "Apply")

    fun buildProjectContext(projectRoot: Path): String {
        val snapshot = analyzeProject(projectRoot)
        val files = walkProject(projectRoot)
            .map { projectRoot.relativize(it).toString().replace('\\', '/') }
            .sorted()

        return buildString {
            appendLine("Project name: ${snapshot.projectName}")
            appendLine("Detected stack: ${snapshot.profile}")
            appendLine()
            appendLine("Top-level entries:")
            snapshot.topLevelEntries.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Entry points:")
            snapshot.entryPoints.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Configuration and docs:")
            snapshot.configAndDocs.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Source roots:")
            snapshot.sourceRoots.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Test roots:")
            snapshot.testRoots.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Suggested checks:")
            snapshot.checkCommands.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Project file map, excluding generated/heavy directories:")
            files.take(800).forEach { appendLine("- `$it`") }
            if (files.size > 800) {
                appendLine("- ...and ${files.size - 800} more files not included to keep the prompt bounded.")
            }
            appendLine()
            appendLine("Source structure summary:")
            summarizeSourceStructure(files).forEach { appendLine("- $it") }
            appendLine()
            appendLine("Representative source files by concern:")
            representativeSourceFiles(files).forEach { appendLine("- `$it`") }
            appendLine()
            appendLine("Concern map:")
            concernMap(files).forEach { (concern, concernFiles) ->
                appendLine("### $concern")
                concernFiles.forEach { appendLine("- `$it`") }
            }
            appendLine()
            appendLine("Important file excerpts:")
            importantFilesForPrompt(files).forEach { relativePath ->
                val file = projectRoot.resolve(relativePath)
                val excerpt = readExcerpt(file)
                if (excerpt.isNotBlank()) {
                    appendLine("### $relativePath")
                    appendLine("```")
                    appendLine(excerpt)
                    appendLine("```")
                }
            }
        }
    }

    fun validate(content: String): CodeMdValidationResult {
        val suggestions = mutableListOf<String>()
        val normalized = content.lowercase()

        requiredSections.forEach { section ->
            if (!hasHeading(content, section)) {
                suggestions += "Добавьте раздел `## $section` или `## 1. $section`, чтобы документ покрывал полный PDCA-цикл."
            }
        }

        if (!normalized.contains("рабочее соглашение") && !normalized.contains("workflow agreement")) {
            suggestions += "Укажите, что CODE.md — рабочее соглашение для разработчиков и LLM-агентов."
        }

        if (!normalized.contains("llm")) {
            suggestions += "Добавьте правила для LLM: что читать сначала, чего избегать и как держать контекст компактным."
        }

        if (!normalized.contains("токен") && !normalized.contains("token")) {
            suggestions += "Опишите, как документ снижает расход токенов через ограниченный контекст и короткий handoff."
        }

        if (!normalized.contains("провер") && !normalized.contains("check") && !normalized.contains("test")) {
            suggestions += "Добавьте правила этапа Control: команды проверки или порядок отчета о пропущенных проверках."
        }

        if (!normalized.contains("карта проекта") && !normalized.contains("entrypoint")) {
            suggestions += "Добавьте карту проекта или список entrypoints, чтобы LLM не сканировала весь репозиторий повторно."
        }

        val lineCount = content.lineSequence().count()
        if (lineCount > 250) {
            suggestions += "Сократите CODE.md: слишком длинный workflow-документ становится дорогим контекстом для LLM."
        }

        return CodeMdValidationResult(
            isValid = suggestions.isEmpty(),
            suggestions = suggestions
        )
    }

    fun normalizeGeneratedMarkdown(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed + "\n"

        val lines = trimmed.lines()
        val withoutOpeningFence = lines.drop(1)
        val withoutClosingFence = if (withoutOpeningFence.lastOrNull()?.trim() == "```") {
            withoutOpeningFence.dropLast(1)
        } else {
            withoutOpeningFence
        }
        return withoutClosingFence.joinToString("\n").trim() + "\n"
    }

    private fun analyzeProject(projectRoot: Path): ProjectSnapshot {
        val projectName = projectRoot.fileName?.name ?: "project"
        val files = walkProject(projectRoot)
        val relativeFiles = files.map { projectRoot.relativize(it).toString().replace('\\', '/') }
        val topLevelEntries = listTopLevel(projectRoot)
        val profile = detectProjectProfile(projectRoot, relativeFiles)
        val entryPoints = detectEntryPoints(relativeFiles)
        val configAndDocs = detectConfigAndDocs(relativeFiles)
        val sourceRoots = detectRoots(relativeFiles, sourceRootMarkers)
        val testRoots = detectRoots(relativeFiles, testRootMarkers)
        val checkCommands = suggestChecks(projectRoot, relativeFiles)

        return ProjectSnapshot(
            projectName = projectName,
            profile = profile,
            topLevelEntries = topLevelEntries,
            entryPoints = entryPoints,
            configAndDocs = configAndDocs,
            sourceRoots = sourceRoots,
            testRoots = testRoots,
            checkCommands = checkCommands
        )
    }

    private fun walkProject(projectRoot: Path): List<Path> {
        if (!Files.isDirectory(projectRoot)) return emptyList()

        Files.walk(projectRoot, MAX_WALK_DEPTH).use { stream ->
            return stream
                .filter { path -> path != projectRoot }
                .filter { path -> path.none { part -> excludedNames.contains(part.name) } }
                .filter { path -> Files.isRegularFile(path) }
                .limit(MAX_PROJECT_FILES.toLong())
                .toList()
        }
    }

    private fun importantFilesForPrompt(files: List<String>): List<String> {
        val preferred = listOf(
            "README.md",
            "CODE.yaml",
            "CODE.md",
            "AGENTS.md",
            "CLAUDE.md",
            "build.gradle.kts",
            "build.gradle",
            "settings.gradle.kts",
            "settings.gradle",
            "gradle.properties",
            "package.json",
            "pyproject.toml",
            "Cargo.toml",
            "go.mod",
            "src/main/resources/META-INF/plugin.xml",
            "app/build.gradle",
            "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml",
            "gradle/libs.versions.toml"
        )

        return (preferred.filter { it in files } +
            files.filter { it.endsWith("/build.gradle") || it.endsWith("/build.gradle.kts") } +
            representativeSourceFiles(files)
        ).distinct().take(45)
    }

    private fun representativeSourceFiles(files: List<String>): List<String> {
        val preferredNameParts = listOf(
            "Application",
            "MainActivity",
            "Activity",
            "Fragment",
            "Screen",
            "ViewModel",
            "Presenter",
            "Controller",
            "Router",
            "Navigator",
            "Navigation",
            "Repository",
            "UseCase",
            "Interactor",
            "Api",
            "Service",
            "Dao",
            "Database",
            "Module",
            "Component",
            "Factory",
            "Config",
            "Settings"
        )

        return files.filter { file ->
            val name = file.substringAfterLast('/')
            val extOk = name.endsWith(".kt") ||
                name.endsWith(".java") ||
                name.endsWith(".ts") ||
                name.endsWith(".tsx") ||
                name.endsWith(".js") ||
                name.endsWith(".jsx") ||
                name.endsWith(".py") ||
                name.endsWith(".go") ||
                name.endsWith(".rs")
            extOk && preferredNameParts.any { part -> name.contains(part, ignoreCase = true) }
        }.take(30)
    }

    private fun summarizeSourceStructure(files: List<String>): List<String> {
        val sourceFiles = files.filter { file ->
            sourceRootMarkers.any { marker -> file.startsWith(marker) } &&
                (file.endsWith(".kt") ||
                    file.endsWith(".java") ||
                    file.endsWith(".ts") ||
                    file.endsWith(".tsx") ||
                    file.endsWith(".js") ||
                    file.endsWith(".py") ||
                    file.endsWith(".go") ||
                    file.endsWith(".rs"))
        }

        return sourceFiles
            .groupBy { file ->
                val parts = file.split('/')
                when {
                    parts.size >= 6 && parts[0] == "app" && parts[1] == "src" -> parts.take(6).joinToString("/")
                    parts.size >= 5 && parts[0] == "src" -> parts.take(5).joinToString("/")
                    parts.size >= 3 -> parts.take(3).joinToString("/")
                    else -> parts.first()
                }
            }
            .entries
            .sortedByDescending { it.value.size }
            .take(20)
            .map { (dir, groupedFiles) -> "`$dir/` — ${groupedFiles.size} files" }
    }

    private fun concernMap(files: List<String>): Map<String, List<String>> {
        val concerns = linkedMapOf(
            "UI" to listOf("activity", "fragment", "screen", "compose", "viewmodel", "view", "ui/"),
            "DI" to listOf("module", "component", "inject", "hilt", "dagger", "koin", "di/"),
            "Navigation" to listOf("navigation", "navigator", "router", "navgraph", "deeplink"),
            "API / Network" to listOf("api", "retrofit", "okhttp", "service", "client", "network", "endpoint"),
            "Storage / Database" to listOf("database", "dao", "room", "entity", "storage", "preferences", "repository"),
            "Domain / Business Logic" to listOf("usecase", "interactor", "domain", "model", "mapper"),
            "Tests" to listOf("test/", "tests/", "androidtest/", "__tests__", "spec/"),
            "Build / Config" to listOf("build.gradle", "settings.gradle", "gradle.properties", "libs.versions", "manifest", "proguard")
        )

        return concerns.mapValues { (_, needles) ->
            files.filter { file ->
                val lower = file.lowercase()
                needles.any { needle -> lower.contains(needle) }
            }.take(12)
        }.filterValues { it.isNotEmpty() }
    }

    private fun readExcerpt(file: Path): String {
        return try {
            if (!Files.isRegularFile(file) || Files.size(file) > 200_000) return ""
            Files.readString(file)
                .lineSequence()
                .take(180)
                .joinToString("\n")
                .take(12_000)
        } catch (_: Exception) {
            ""
        }
    }

    private fun listTopLevel(projectRoot: Path): List<String> {
        if (!Files.isDirectory(projectRoot)) return emptyList()

        Files.list(projectRoot).use { stream ->
            return stream
                .filter { path -> !excludedNames.contains(path.fileName.name) }
                .sorted(compareBy<Path> { !it.isDirectory() }.thenBy { it.fileName.name.lowercase() })
                .limit(MAX_LIST_ITEMS.toLong())
                .map { path ->
                    val suffix = if (path.isDirectory()) "/" else ""
                    "`${path.fileName.name}$suffix`"
                }
                .toList()
        }
    }

    private fun detectProjectProfile(projectRoot: Path, files: List<String>): String {
        val markers = mutableListOf<String>()

        if (exists(projectRoot, "build.gradle.kts") && files.any { it == "src/main/resources/META-INF/plugin.xml" }) {
            markers += "IntelliJ Platform plugin, Gradle Kotlin DSL, Kotlin/JVM"
        } else if (exists(projectRoot, "build.gradle.kts")) {
            markers += "Gradle Kotlin DSL"
        }
        if (exists(projectRoot, "build.gradle")) markers += "Gradle"
        if (exists(projectRoot, "pom.xml")) markers += "Maven"
        if (exists(projectRoot, "package.json")) markers += "Node.js"
        if (exists(projectRoot, "pyproject.toml") || exists(projectRoot, "requirements.txt")) markers += "Python"
        if (exists(projectRoot, "Cargo.toml")) markers += "Rust"
        if (exists(projectRoot, "go.mod")) markers += "Go"
        if (files.any { it.endsWith(".kt") }) markers += "Kotlin"
        if (files.any { it.endsWith(".java") }) markers += "Java"
        if (files.any { it.endsWith(".ts") || it.endsWith(".tsx") }) markers += "TypeScript"

        return markers.distinct()
            .ifEmpty { listOf("не определен автоматически; начните с анализа структуры репозитория") }
            .joinToString(", ")
    }

    private fun detectEntryPoints(files: List<String>): List<String> {
        val exactNames = setOf(
            "src/main/resources/META-INF/plugin.xml",
            "src/main/AndroidManifest.xml",
            "package.json",
            "build.gradle.kts",
            "build.gradle",
            "settings.gradle.kts",
            "settings.gradle",
            "pom.xml",
            "pyproject.toml",
            "Cargo.toml",
            "go.mod",
            "main.py",
            "app.py"
        )
        val namePatterns = listOf("Application.", "App.", "Main.", "Server.", "index.", "main.")

        return files
            .filter { file ->
                file in exactNames ||
                        namePatterns.any { pattern ->
                            file.substringAfterLast('/').contains(pattern, ignoreCase = true)
                        }
            }
            .take(MAX_LIST_ITEMS)
            .map { "`$it`" }
    }

    private fun detectConfigAndDocs(files: List<String>): List<String> {
        val configNames = setOf(
            "README.md",
            "CODE.md",
            "AGENTS.md",
            "CLAUDE.md",
            "CODE.yaml",
            ".gitignore",
            "gradle.properties",
            "package.json",
            "tsconfig.json",
            "vite.config.ts",
            "webpack.config.js",
            "pyproject.toml",
            "requirements.txt",
            "Dockerfile",
            "docker-compose.yml"
        )

        return files
            .filter { file ->
                val name = file.substringAfterLast('/')
                name in configNames ||
                        name.endsWith(".yml") ||
                        name.endsWith(".yaml") ||
                        name.endsWith(".toml") ||
                        name.endsWith(".properties")
            }
            .take(MAX_LIST_ITEMS)
            .map { "`$it`" }
    }

    private fun detectRoots(files: List<String>, markers: List<String>): List<String> =
        files
            .asSequence()
            .mapNotNull { file -> markers.firstOrNull { marker -> file.startsWith(marker) } }
            .distinct()
            .take(MAX_LIST_ITEMS)
            .map { "`$it`" }
            .toList()

    private fun suggestChecks(projectRoot: Path, files: List<String>): List<String> {
        val checks = mutableListOf<String>()

        when {
            exists(projectRoot, "gradlew.bat") -> {
                checks += ".\\gradlew.bat test"
                checks += ".\\gradlew.bat build"
                if (files.any { it == "src/main/resources/META-INF/plugin.xml" }) {
                    checks += ".\\gradlew.bat verifyPlugin"
                }
            }

            exists(projectRoot, "gradlew") -> {
                checks += "./gradlew test"
                checks += "./gradlew build"
                if (files.any { it == "src/main/resources/META-INF/plugin.xml" }) {
                    checks += "./gradlew verifyPlugin"
                }
            }

            exists(projectRoot, "package.json") -> {
                checks += "npm test"
                checks += "npm run build"
            }

            exists(projectRoot, "pyproject.toml") -> checks += "python -m pytest"
            exists(projectRoot, "go.mod") -> checks += "go test ./..."
            exists(projectRoot, "Cargo.toml") -> checks += "cargo test"
        }

        return checks.ifEmpty { listOf("запустить документированную команду сборки/тестов проекта") }
    }

    private fun StringBuilder.appendBulletList(items: List<String>, emptyText: String) {
        if (items.isEmpty()) {
            appendLine("- $emptyText")
        } else {
            items.forEach { appendLine("- $it") }
        }
    }

    private fun hasHeading(content: String, section: String): Boolean {
        val pattern = Regex("^#{1,3}\\s+(?:\\d+\\.?\\s+)?${Regex.escape(section)}(?:\\s+.*)?$", RegexOption.IGNORE_CASE)
        return content.lineSequence().any { pattern.matches(it.trim()) }
    }

    private fun exists(projectRoot: Path, relativePath: String): Boolean =
        Files.exists(projectRoot.resolve(relativePath))

    private val excludedNames = setOf(
        ".git",
        ".gradle",
        ".idea",
        ".intellijPlatform",
        ".kotlin",
        "build",
        "out",
        "target",
        "node_modules",
        ".venv",
        "venv",
        "dist",
        ".next",
        ".cache"
    )

    private val sourceRootMarkers = listOf(
        "src/main/",
        "src/",
        "app/",
        "lib/",
        "server/",
        "client/",
        "packages/",
        "cmd/",
        "internal/"
    )

    private val testRootMarkers = listOf(
        "src/test/",
        "src/androidTest/",
        "test/",
        "tests/",
        "__tests__/",
        "spec/"
    )
}

data class CodeMdValidationResult(
    val isValid: Boolean,
    val suggestions: List<String>
)

private data class ProjectSnapshot(
    val projectName: String,
    val profile: String,
    val topLevelEntries: List<String>,
    val entryPoints: List<String>,
    val configAndDocs: List<String>,
    val sourceRoots: List<String>,
    val testRoots: List<String>,
    val checkCommands: List<String>
)
