package tr.borsatakip.v5.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class B136BistScanUiConsistencySourceTest {
    private fun mainRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(cwd, "src/main"), File(cwd, "app/src/main"), cwd.parentFile?.let { File(it, "app/src/main") })
            .filterNotNull().first { it.isDirectory }
    }

    private fun source(path: String): String = File(mainRoot(), path).readText()

    @Test fun heroAndStatusAreRuntimeDriven() {
        val activity = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        val layout = source("res/layout/activity_bist_scan.xml")
        assertTrue(activity.contains("renderScanState"))
        assertTrue(activity.contains("renderIdleScanState"))
        assertTrue(activity.contains("R.id.txtScanStatusDot"))
        assertTrue(layout.contains("@+id/txtScanStatusDot"))
        assertFalse(layout.contains("android:text=\"BIST hisseleri taranıyor\""))
    }

    @Test fun stoppedStateIsTurkishAndNotGreen() {
        val activity = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertTrue(activity.contains("renderScanState(\"Durduruldu\", \"Manuel tarama durduruldu\", R.color.text_muted)"))
        assertFalse(activity.contains("status.text = \"STOPPED\""))
    }

    @Test fun fallbackButtonIsCompactAndProviderWarningRemainsElsewhere() {
        val activity = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertTrue(activity.contains("\"▶  ${'$'}{tf.label} ANALİZİ BAŞLAT  ›\""))
        assertTrue(activity.contains("Yahoo Finance • YEDEK/GECİKMELİ"))
    }

    @Test fun bottomNavigationKeepsViopAndHighlightsStocksArea() {
        val activity = source("java/tr/borsatakip/v5/ui/BistScanActivity.kt")
        assertTrue(activity.contains("R.id.navBist"))
        assertFalse(activity.contains("text = \"⌕\\nBIST Tarama\""))
    }

    @Test fun heroCardCanGrowAndScrollContentHasBottomBreathingRoom() {
        val layout = source("res/layout/activity_bist_scan.xml")
        assertTrue(layout.contains("android:minHeight=\"148dp\""))
        assertTrue(layout.contains("android:paddingBottom=\"36dp\""))
    }
}
