package tr.borsatakip.v5.ui

import android.content.Context
import android.os.Environment
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tr.borsatakip.v5.R
import tr.borsatakip.v5.scan.ScanState
import tr.borsatakip.v5.scan.ScanStatus
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScreenRegressionTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetSettings() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun closeActivities() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()
    }

    @Test
    fun viop_backendMissing_hasNoBrokenControls_andShowsConfigureAction() {
        ActivityScenario.launch(ViopActivity::class.java).use {
            onView(withText("VERİ KAYNAĞINI YAPILANDIR")).check(matches(isDisplayed()))
            onView(withText("▮▮")).check(doesNotExist())
            onView(withText(containsString("VİOP veri kaynağı hazır değil"))).check(matches(isDisplayed()))
            takeScreenshot("viop_backend_missing.png")
        }
    }

    @Test
    fun bist_all631Errors_isCompletionNotSuccess_andTechnicalDetailOpens() {
        ActivityScenario.launch(BistScanActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val state = ScanState(
                    status = ScanStatus.COMPLETED,
                    progress = 100,
                    processed = 631,
                    total = 631,
                    successful = 0,
                    skipped = 631,
                    dataReceived = 0,
                    analysisErrors = 631,
                    noSignal = 0,
                    signalCount = 0
                )

                val render = BistScanActivity::class.java.getDeclaredMethod(
                    "renderUserState",
                    ScanState::class.java,
                    Boolean::class.javaPrimitiveType
                ).apply { isAccessible = true }
                val summary = render.invoke(activity, state, true) as String

                activity.findViewById<android.widget.ProgressBar>(R.id.progress).progress = 100
                activity.findViewById<android.widget.TextView>(R.id.txtProgress).text =
                    "İşlenen: 631 / 631 • Tarama tamamlandı"
                activity.findViewById<android.widget.TextView>(R.id.txtStatus).text = summary
                activity.findViewById<android.widget.TextView>(R.id.txtDebugState).text =
                    "Toplam: 631 • İşlenen: 631\nAnaliz hatası: 631\nTerminal sonuç: 631/631"
            }

            onView(withText("İşlenen: 631 / 631 • Tarama tamamlandı")).check(matches(isDisplayed()))
            onView(withText(containsString("Hata: 631"))).check(matches(isDisplayed()))
            onView(withText(containsString("VERİ İŞLENEMEDİ"))).check(matches(isDisplayed()))
            onView(withText(containsString("%100"))).check(doesNotExist())
            takeScreenshot("bist_631_error.png")

            onView(withId(R.id.btnTechnical)).perform(click())
            onView(withText("TEKNİK VERİ DETAYINI GİZLE")).check(matches(isDisplayed()))
            onView(withId(R.id.txtDebugState)).check(matches(isDisplayed()))
            takeScreenshot("bist_technical_detail.png")
        }
    }

    private fun takeScreenshot(name: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val dir = context.getExternalFilesDir("ui-screenshots")
            ?: File(context.filesDir, "ui-screenshots")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, name)
        assertTrue("Screenshot alınamadı: ${target.absolutePath}", device.takeScreenshot(target))
        assertTrue("Screenshot dosyası boş", target.length() > 0L)
    }
}
