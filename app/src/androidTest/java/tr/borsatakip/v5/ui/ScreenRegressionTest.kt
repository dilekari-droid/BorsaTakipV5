package tr.borsatakip.v5.ui

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.favorites.FavoriteStock
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.TechnicalSnapshot
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
        AppSession.lastOpportunities = emptyList()
    }

    @After
    fun closeActivities() {
        AppSession.lastOpportunities = emptyList()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()
    }

    @Test
    fun viop_backendMissing_experimentalModeRemainsUsableWithoutFakePrices() {
        ActivityScenario.launch(ViopActivity::class.java).use {
            onView(withText("DENEYSEL VİOP AKIŞINI ÇALIŞTIR")).check(matches(isDisplayed()))
            onView(withText("DENEYSEL SÖZLEŞME METADATA'SI EKLE")).check(matches(isDisplayed()))
            onView(withText("▮▮")).check(doesNotExist())
            onView(withText(containsString("DENEYSEL VİOP MODU"))).check(matches(isDisplayed()))
            takeScreenshot("viop_experimental_mode.png")
        }
    }

    @Test
    fun settings_lrcDefaultsAndToggles_areStable() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.lrcEnabled)).perform(scrollTo()).check(matches(isChecked()))
            onView(withId(R.id.lrcSigma1)).perform(scrollTo()).check(matches(not(isChecked())))
            onView(withId(R.id.lrcSigma2)).perform(scrollTo()).check(matches(isChecked()))
            onView(withId(R.id.lrcSigma3)).perform(scrollTo()).check(matches(not(isChecked())))
            onView(withId(R.id.lrcPearson)).perform(scrollTo()).check(matches(isChecked()))
            onView(withId(R.id.lrcTrendColor)).perform(scrollTo()).check(matches(isChecked()))

            onView(withId(R.id.lrcSigma1)).perform(scrollTo(), click())
            onView(withId(R.id.lrcSigma3)).perform(scrollTo(), click())
            onView(withId(R.id.lrcFill)).perform(scrollTo(), click())
            onView(withId(R.id.save)).perform(scrollTo(), click())

            onView(withId(R.id.lrcSigma1)).perform(scrollTo()).check(matches(isChecked()))
            onView(withId(R.id.lrcSigma3)).perform(scrollTo()).check(matches(isChecked()))
            onView(withId(R.id.lrcFill)).perform(scrollTo()).check(matches(isChecked()))
            takeScreenshot("settings_lrc.png")
        }
    }

    @Test
    fun mainTodayTrendColor_isOnlyAppliedToFirstLine() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val method = MainActivity::class.java.getDeclaredMethod("buildTodayText", List::class.java)
                    .apply { isAccessible = true }
                val text = method.invoke(
                    activity,
                    listOf(testOpportunity("HALKB", "LONG"), testOpportunity("THYAO", "SHORT"))
                ) as Spanned
                val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
                assertEquals(2, spans.size)
                val green = ContextCompat.getColor(activity, R.color.green)
                val red = ContextCompat.getColor(activity, R.color.red)
                assertTrue(spans.any { it.foregroundColor == green })
                assertTrue(spans.any { it.foregroundColor == red })
                spans.forEach { span ->
                    val colored = text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString()
                    assertTrue("Trend rengi alt satıra taşmamalı: $colored", !colored.contains('\n'))
                }
            }
        }
    }

    @Test
    fun favoriteTrendColor_usesDirectionNotScoreOrDailyChange() {
        val parent = FrameLayout(context)
        val long = testOpportunity("LONGX", "LONG", score = 61, dailyChange = -4.0)
        val short = testOpportunity("SHORTX", "SHORT", score = 95, dailyChange = 5.0)
        val adapter = FavoriteAdapter(
            listOf(FavoriteStock("LONGX") to long, FavoriteStock("SHORTX") to short),
            onRemove = {},
            onOpen = {}
        )
        val longHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(longHolder, 0)
        assertEquals(ContextCompat.getColor(context, R.color.green), longHolder.symbol.currentTextColor)
        assertEquals(ContextCompat.getColor(context, R.color.text_secondary), longHolder.details.currentTextColor)

        val shortHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(shortHolder, 1)
        assertEquals(ContextCompat.getColor(context, R.color.red), shortHolder.symbol.currentTextColor)
        assertEquals(ContextCompat.getColor(context, R.color.text_secondary), shortHolder.details.currentTextColor)
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
                    signalCount = 0,
                    researchCandidateCount = 0
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
            onView(withText(containsString("%100"))).check(doesNotExist())
            takeScreenshot("bist_631_error.png")

            onView(withId(R.id.btnTechnical)).perform(click())
            onView(withText("TEKNİK VERİ DETAYINI GİZLE")).check(matches(isDisplayed()))
            onView(withId(R.id.txtDebugState)).check(matches(isDisplayed()))
            takeScreenshot("bist_technical_detail.png")
        }
    }

    private fun testOpportunity(
        symbol: String,
        direction: String,
        score: Int = 80,
        dailyChange: Double = 0.0
    ): Opportunity = Opportunity(
        symbol = symbol,
        companyName = symbol,
        price = 100.0,
        dailyChangePct = dailyChange,
        score = score,
        riskScore = 20,
        direction = direction,
        technicalLabel = "Test",
        volumeLabel = "1.00x",
        kapLabel = "Veri yok",
        liquidityLabel = "Test",
        support = 90.0,
        resistance = 110.0,
        source = "test",
        dataTimestamp = System.currentTimeMillis(),
        candles = emptyList(),
        technical = TechnicalSnapshot(
            ema20 = 101.0,
            ema50 = 100.0,
            ema200 = 99.0,
            rsi14 = 55.0,
            macd = 1.0,
            macdSignal = 0.5,
            bbUpper = null,
            bbLower = null,
            atr14 = null,
            vwap = null,
            volumeRatio = null,
            support = 90.0,
            resistance = 110.0
        ),
        dataConfidenceScore = 80,
        dataConfidenceLabel = "Orta",
        analysisMode = "ARAŞTIRMA / GECİKMELİ",
        signalEligibleRealtime = false,
        finalSignalScore = score,
        isRealtime = false
    )

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
