package tr.borsatakip.v5.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prevents UI version drift. The application version may only come from Gradle/BuildConfig.
 * Any literal 5.1.x application-version token under app/src/main is rejected at unit-test time.
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

        val versionLiteral = Regex("(?i)\\bV?5\\.1\\.\\d+\\b")
        val textExtensions = setOf("kt", "java", "xml")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in textExtensions }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if (versionLiteral.containsMatchIn(line)) {
                        "${file.relativeTo(sourceRoot).path}:${index + 1}: ${line.trim()}"
                    } else null
                }
            }
            .toList()

        assertTrue(
            "UI/main source içinde sabit uygulama sürümü bulundu. Sürüm yalnız BuildConfig.VERSION_NAME üzerinden gelmelidir:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
