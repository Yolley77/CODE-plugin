package ru.codeplugin.services

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

object JacocoCoverageReader {

    /**
     * Возвращает покрытие от 0.0 до 1.0 по отчёту Jacoco.
     * Ищем counter type="INSTRUCTION" (если нет – пытаемся type="LINE").
     */
    fun readCoverage(reportPath: Path): Double? {
        if (!Files.exists(reportPath)) return null

        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(reportPath.toFile())

            val counters = doc.getElementsByTagName("counter")
            var covered: Long? = null
            var missed: Long? = null

            // Сначала пробуем INSTRUCTION
            for (i in 0 until counters.length) {
                val node = counters.item(i)
                if (node is Element && node.getAttribute("type") == "INSTRUCTION") {
                    covered = node.getAttribute("covered").toLongOrNull()
                    missed = node.getAttribute("missed").toLongOrNull()
                    break
                }
            }

            // Если INSTRUCTION не нашли – пробуем LINE
            if (covered == null || missed == null) {
                for (i in 0 until counters.length) {
                    val node = counters.item(i)
                    if (node is Element && node.getAttribute("type") == "LINE") {
                        covered = node.getAttribute("covered").toLongOrNull()
                        missed = node.getAttribute("missed").toLongOrNull()
                        break
                    }
                }
            }

            if (covered == null || missed == null) return null
            val total = covered + missed
            if (total <= 0) return null

            covered.toDouble() / total.toDouble()
        } catch (_: Exception) {
            null
        }
    }
}
