package tr.borsatakip.v5.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import tr.borsatakip.v5.R

/**
 * Regression for the historical java.lang.VerifyError in
 * BistScanActivity$onCreate$1$1.invokeSuspend(Object).
 * The activity must load and the scan button click path must execute on ART without verifier rejection.
 * Network/backend success is intentionally not required for this verifier smoke test.
 */
@RunWith(AndroidJUnit4::class)
class BistScanActivityRuntimeTest {
    @Test
    fun activityLoadsAndScanButtonClickDoesNotTriggerVerifierCrash() {
        ActivityScenario.launch(BistScanActivity::class.java).use {
            onView(withId(R.id.btnStartScan)).check(matches(isDisplayed()))
            onView(withId(R.id.btnStartScan)).perform(click())
            onView(withId(R.id.txtStatus)).check(matches(isDisplayed()))
        }
    }
}
