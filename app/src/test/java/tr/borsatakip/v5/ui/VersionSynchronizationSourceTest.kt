package tr.borsatakip.v5.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prevents UI version drift. The application version may only come from Gradle/BuildConfig.
 * Runtime string/XML literals containing a 5.1.x application version under app/src/main are rejected.
 * Comments/documentation are intentionally ignored because they are not rendered UI values.
 */
class VersionSynchronizationSourceTest {
    @Test
    fun mainSourcesDoNotHardcodeApplicationVersion() {
        val cwd = File(System.getProperty("user.dir"))
        val sourceRoot = sequenceOf(
            File(cwd, "src/main"),
            File(cwd, "app/src/main"),
            cwd.parentFile?.let { File(it, "app/src/main") }
        ).filterNotNull().firstOrNull { it.isDirectory }

        assertTrue("app/src/main bulunamadı. Çalışma dizini: ${cwd.absolutePath}", sourceRoot != null)
        sourceRoot!!

        val quotedVersionLiteral = Regex("[\"']\\s*V?5\\.1\\.\\d+[^\"']*[\"']", RegexOption.IGNORE_CASE)
        val textExtensions = setOf("kt", "java", "xml")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in textExtensions }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, rawLine ->
                    val line = rawLine.trim()
                    val isCommentOnly = line.startsWith("//") ||
                        line.startsWith("/*") || line.startsWith("*") || line.startsWith("*/") ||
                        line.startsWith("<!--") || line.startsWith("-->")
                    if (!isCommentOnly && quotedVersionLiteral.containsMatchIn(line)) {
                        "${file.relativeTo(sourceRoot).path}:${index + 1}: $line"
                    } else null
                }
            }
            .toList()

        assertTrue(
            "UI/main source içinde sabit uygulama sürüm literal'i bulundu. Sürüm yalnız BuildConfig.VERSION_NAME üzerinden gelmelidir:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
