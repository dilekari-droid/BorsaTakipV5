package tr.borsatakip.v5

import android.app.Application
import androidx.work.*
import tr.borsatakip.v5.worker.OpportunityWorker
import java.util.concurrent.TimeUnit

class BorsaTakipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        scheduleWorker()
    }
    private fun scheduleWorker() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("notifications", false)) return
        val minutes = prefs.getInt("refresh_minutes", 60).coerceAtLeast(15)
        val req = PeriodicWorkRequestBuilder<OpportunityWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("opportunity_watch", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
