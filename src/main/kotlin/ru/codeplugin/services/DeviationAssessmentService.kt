package ru.codeplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import git4idea.GitUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Service(Service.Level.PROJECT)
class DeviationAssessmentService(private val project: Project) {

    fun assess(currentFileAvailable: Boolean? = null): DeviationAssessmentReport {
        val cfg = CodeConfigService.getInstance(project).cfg()
        val branch = currentBranch()
        val branchAgeHours = branch?.let { CodeBranchLifecycleService.getInstance(project).getBranchAgeHours(it) }

        val stages = listOf(
            assessPrepare(cfg, branch, branchAgeHours),
            assessDevelop(cfg, currentFileAvailable),
            assessControl(cfg),
            assessApply(cfg, branchAgeHours)
        )

        val availableStages = stages.filter { it.state != null }
        val integralDeviation = availableStages
            .mapNotNull { it.deviation }
            .takeIf { it.isNotEmpty() }
            ?.average()
        val worstStage = availableStages
            .filter { it.deviation != null }
            .maxByOrNull { it.deviation ?: 0.0 }
            ?.stage
        val missingMetrics = stages.flatMap { stage ->
            stage.missingMetrics.map { metric -> "${stage.stage.id}.$metric" }
        }

        return DeviationAssessmentReport(
            generatedAt = LocalDateTime.now(),
            projectName = project.name,
            branch = branch,
            stages = stages,
            missingMetrics = missingMetrics,
            integralDeviation = integralDeviation,
            worstStage = worstStage
        )
    }

    fun writeMarkdownReport(report: DeviationAssessmentReport): Path {
        val basePath = project.basePath ?: error("Project base path is unavailable")
        val reportsDir = Paths.get(basePath, "build", "reports", "code")
        Files.createDirectories(reportsDir)

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val reportPath = reportsDir.resolve("deviation-assessment-$timestamp.md")
        Files.writeString(reportPath, report.toMarkdown())
        return reportPath
    }

    private fun assessPrepare(
        cfg: CodeConfig,
        branch: String?,
        branchAgeHours: Double?
    ): DeviationStageReport {
        val branchPattern = runCatching { branchPattern(cfg.prepare.branch_format) }.getOrNull()
        val metrics = listOf(
            metric(
                stage = DeviationStage.PREPARE,
                name = "branch_name_match",
                kind = DeviationMetricKind.BINARY,
                actual = branch,
                target = cfg.prepare.branch_format,
                normalizedValue = if (branch != null && branchPattern != null) {
                    binary(branchPattern.matches(branch))
                } else {
                    null
                },
                missingReason = when {
                    branch == null -> "current Git branch is unavailable"
                    branchPattern == null -> "branch_format cannot be converted to regex"
                    else -> null
                }
            ),
            metric(
                stage = DeviationStage.PREPARE,
                name = "branch_age_hours",
                kind = DeviationMetricKind.DESTIMULATING,
                actual = branchAgeHours,
                target = cfg.prepare.max_branch_age_hours.toDouble(),
                normalizedValue = normalizeDestimulating(branchAgeHours, cfg.prepare.max_branch_age_hours.toDouble()),
                missingReason = if (branchAgeHours == null) "branch lifecycle start time is unavailable" else null
            )
        )

        return stageReport(DeviationStage.PREPARE, metrics)
    }

    private fun assessDevelop(
        cfg: CodeConfig,
        currentFileAvailable: Boolean?
    ): DeviationStageReport {
        val checkEnabled = cfg.develop.require_code_style_check
        val actual = if (currentFileAvailable == null) {
            checkEnabled
        } else {
            checkEnabled && currentFileAvailable
        }
        val target = if (currentFileAvailable == null) {
            "require_code_style_check=true"
        } else {
            "require_code_style_check=true and current file available"
        }

        val metrics = listOf(
            metric(
                stage = DeviationStage.DEVELOP,
                name = "code_style_check_ready",
                kind = DeviationMetricKind.BINARY,
                actual = actual,
                target = target,
                normalizedValue = binary(actual)
            )
        )

        return stageReport(DeviationStage.DEVELOP, metrics)
    }

    private fun assessControl(cfg: CodeConfig): DeviationStageReport {
        val reportPath = project.basePath?.let { Paths.get(it, cfg.control.coverage.report_path) }
        val coverageReportExists = reportPath?.let { reportArtifactExists(it) }
        val coverage = reportPath?.let { JacocoCoverageReader.readCoverage(it) }
        val metrics = mutableListOf(
            metric(
                stage = DeviationStage.CONTROL,
                name = "coverage_report_exists",
                kind = DeviationMetricKind.BINARY,
                actual = coverageReportExists,
                target = cfg.control.coverage.report_path,
                normalizedValue = coverageReportExists?.let { binary(it) },
                missingReason = if (reportPath == null) "project base path is unavailable" else null
            ),
            metric(
                stage = DeviationStage.CONTROL,
                name = "coverage_overall",
                kind = DeviationMetricKind.STIMULATING,
                actual = coverage,
                target = cfg.control.coverage.min_overall,
                normalizedValue = normalizeStimulating(coverage, cfg.control.coverage.min_overall),
                missingReason = when {
                    reportPath == null -> "project base path is unavailable"
                    coverage == null -> "JaCoCo XML coverage report is unavailable or unreadable: $reportPath"
                    else -> null
                }
            )
        )
        metrics += metric(
            stage = DeviationStage.CONTROL,
            name = "required_checks_configured",
            kind = DeviationMetricKind.BINARY,
            actual = cfg.control.required_checks.isNotEmpty(),
            target = "control.required_checks is not empty",
            normalizedValue = binary(cfg.control.required_checks.isNotEmpty())
        )

        return stageReport(DeviationStage.CONTROL, metrics)
    }

    private fun assessApply(
        cfg: CodeConfig,
        branchAgeHours: Double?
    ): DeviationStageReport {
        val changedFilesCount = changedFilesCount()
        val maxBranchAgeHours = cfg.prepare.max_branch_age_hours.toDouble()
        val metrics = listOf(
            metric(
                stage = DeviationStage.APPLY,
                name = "changed_files_count",
                kind = DeviationMetricKind.DESTIMULATING,
                actual = changedFilesCount,
                target = cfg.apply.max_files_changed.toDouble(),
                normalizedValue = normalizeDestimulating(
                    changedFilesCount?.toDouble(),
                    cfg.apply.max_files_changed.toDouble()
                ),
                missingReason = if (changedFilesCount == null) "changed files are unavailable from ChangeListManager" else null
            ),
            metric(
                stage = DeviationStage.APPLY,
                name = "branch_age_hours",
                kind = DeviationMetricKind.DESTIMULATING,
                actual = branchAgeHours,
                target = maxBranchAgeHours,
                normalizedValue = normalizeDestimulating(branchAgeHours, maxBranchAgeHours),
                missingReason = if (branchAgeHours == null) "branch lifecycle start time is unavailable" else null
            )
        )

        return stageReport(DeviationStage.APPLY, metrics)
    }

    private fun metric(
        stage: DeviationStage,
        name: String,
        kind: DeviationMetricKind,
        actual: Any?,
        target: Any?,
        normalizedValue: Double?,
        missingReason: String? = null,
        weight: Double = 1.0
    ): DeviationMetricReport =
        DeviationMetricReport(
            stage = stage,
            name = name,
            kind = kind,
            actual = actual?.toString(),
            target = target?.toString(),
            normalizedValue = normalizedValue,
            deviation = normalizedValue?.let { 1.0 - it },
            weight = weight,
            missingReason = if (normalizedValue == null) missingReason ?: "metric data is unavailable" else null
        )

    private fun stageReport(stage: DeviationStage, metrics: List<DeviationMetricReport>): DeviationStageReport {
        val available = metrics.filter { it.normalizedValue != null && it.weight > 0.0 }
        val state = weightedAverage(available)
        return DeviationStageReport(
            stage = stage,
            metrics = metrics,
            missingMetrics = metrics.filter { it.normalizedValue == null }.map { it.name },
            state = state,
            deviation = state?.let { 1.0 - it }
        )
    }

    private fun weightedAverage(metrics: List<DeviationMetricReport>): Double? {
        if (metrics.isEmpty()) return null
        val totalWeight = metrics.sumOf { it.weight }
        if (totalWeight <= 0.0) return null
        return metrics.sumOf { (it.normalizedValue ?: 0.0) * it.weight } / totalWeight
    }

    private fun changedFilesCount(): Int? =
        runCatching {
            ChangeListManager.getInstance(project)
                .defaultChangeList
                .changes
                .mapNotNull { it.virtualFile ?: it.beforeRevision?.file?.virtualFile }
                .distinctBy { it.path }
                .size
        }.getOrNull()

    private fun currentBranch(): String? =
        GitUtil.getRepositories(project).firstOrNull()?.currentBranchName

    private fun reportArtifactExists(path: Path): Boolean {
        if (!Files.exists(path)) return false
        if (Files.isRegularFile(path)) return true
        if (!Files.isDirectory(path)) return false

        Files.list(path).use { stream ->
            return stream.findAny().isPresent
        }
    }

    companion object {
        fun getInstance(project: Project): DeviationAssessmentService = project.service()

        fun branchPattern(branchFormat: String): Regex =
            branchFormat
                .replace("\${issue}", "[A-Z]+-\\d+")
                .replace("\${slug}", "[a-z0-9]+(?:-[a-z0-9]+)*")
                .toRegex()

        fun normalizeStimulating(value: Double?, target: Double): Double? {
            if (value == null || target <= 0.0) return null
            return (value / target).coerceAtMost(1.0)
        }

        fun normalizeDestimulating(value: Double?, target: Double): Double? {
            if (value == null || target < 0.0) return null
            if (value == 0.0) return 1.0
            if (target == 0.0) return 0.0
            return (target / value).coerceAtMost(1.0)
        }

        fun binary(value: Boolean): Double = if (value) 1.0 else 0.0
    }
}

enum class DeviationStage(val id: String, val label: String) {
    PREPARE("prepare", "Prepare"),
    DEVELOP("develop", "Develop"),
    CONTROL("control", "Control"),
    APPLY("apply", "Apply");

    val stateSymbol: String
        get() = "x${label.first()}"

    val deviationSymbol: String
        get() = "d${label.first()}"
}

enum class DeviationMetricKind {
    STIMULATING,
    DESTIMULATING,
    BINARY
}

data class DeviationAssessmentReport(
    val generatedAt: LocalDateTime,
    val projectName: String,
    val branch: String?,
    val stages: List<DeviationStageReport>,
    val missingMetrics: List<String>,
    val integralDeviation: Double?,
    val worstStage: DeviationStage?
) {
    fun toMarkdown(): String = buildString {
        appendLine("# CODE Deviation Assessment")
        appendLine()
        appendLine("- Project: `${projectName}`")
        appendLine("- Generated at: `${generatedAt}`")
        appendLine("- Branch: `${branch ?: "unknown"}`")
        appendLine("- Integral deviation F: ${integralDeviation.formatOrUnknown()}")
        appendLine("- Worst stage: ${worstStage?.label ?: "unknown"}")
        appendLine("- Missing metrics: ${if (missingMetrics.isEmpty()) "none" else missingMetrics.joinToString()}")
        appendLine()
        appendLine("Missing metrics are excluded from calculation; available metric weights are renormalized inside each stage.")
        appendLine()

        stages.forEach { stage ->
            appendLine("## ${stage.stage.label}")
            appendLine()
            appendLine("- State ${stage.stage.stateSymbol}: ${stage.state.formatOrUnknown()}")
            appendLine("- Deviation ${stage.stage.deviationSymbol}: ${stage.deviation.formatOrUnknown()}")
            appendLine("- Missing metrics: ${if (stage.missingMetrics.isEmpty()) "none" else stage.missingMetrics.joinToString()}")
            appendLine()
            appendLine("| Metric | Kind | Actual | Target | Normalized | Deviation | Weight | Missing reason |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
            stage.metrics.forEach { metric ->
                appendLine(
                    "| ${metric.name.escapeMarkdown()} " +
                            "| ${metric.kind} " +
                            "| ${metric.actual.orDash().escapeMarkdown()} " +
                            "| ${metric.target.orDash().escapeMarkdown()} " +
                            "| ${metric.normalizedValue.formatOrUnknown()} " +
                            "| ${metric.deviation.formatOrUnknown()} " +
                            "| ${metric.weight.formatOrUnknown()} " +
                            "| ${metric.missingReason.orDash().escapeMarkdown()} |"
                )
            }
            appendLine()
        }
    }
}

data class DeviationStageReport(
    val stage: DeviationStage,
    val metrics: List<DeviationMetricReport>,
    val missingMetrics: List<String>,
    val state: Double?,
    val deviation: Double?
)

data class DeviationMetricReport(
    val stage: DeviationStage,
    val name: String,
    val kind: DeviationMetricKind,
    val actual: String?,
    val target: String?,
    val normalizedValue: Double?,
    val deviation: Double?,
    val weight: Double,
    val missingReason: String?
)

private fun Double?.formatOrUnknown(): String =
    this?.let { String.format(Locale.US, "%.3f", it) } ?: "unknown"

private fun String?.orDash(): String = this ?: "-"

private fun String.escapeMarkdown(): String =
    replace("|", "\\|").replace("\n", " ")
