package tr.borsatakip.v5.worker

import android.Manifest
import android.app.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import tr.borsatakip.v5.analysis.OpportunityEngine
import tr.borsatakip.v5.data.MobileMarketDataProvider

class OpportunityWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){
    override suspend fun doWork():Result{
        val prefs=applicationContext.getSharedPreferences("favorites",Context.MODE_PRIVATE)
        val symbols=prefs.getStringSet("bist",emptySet())?.take(20).orEmpty()
        if(symbols.isEmpty()) return Result.success()
        return try {
            val provider=MobileMarketDataProvider(applicationContext)
            val hits=symbols.mapNotNull{ s -> provider.fetchOne(s)?.let{ stock -> OpportunityEngine.score(stock) } }
                .filter{ it.score>=80 && it.riskScore<=60 }
            if(hits.isNotEmpty()) notify(applicationContext,"BORSA TAKİP fırsat uyarısı",hits.take(3).joinToString(" • "){"${it.symbol} ${it.score}/100 ${it.direction}"})
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
    companion object{
        fun notify(context:Context,title:String,text:String){
            if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
            val id="opportunities";val nm=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(NotificationChannel(id,"Fırsat Bildirimleri",NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(5001,NotificationCompat.Builder(context,id).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build())
        }
    }
}
