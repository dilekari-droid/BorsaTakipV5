package tr.borsatakip.v5.data
import android.content.Context
class SettingsStore(c:Context){ private val p=c.getSharedPreferences("settings",Context.MODE_PRIVATE)
    var baseUrl:String get()=p.getString("base_url","")?:""; set(v)=p.edit().putString("base_url",v.trim().removeSuffix("/")).apply()
    var apiKey:String get()=p.getString("api_key","")?:""; set(v)=p.edit().putString("api_key",v.trim()).apply()
    var refreshMinutes:Int get()=p.getInt("refresh_minutes",60); set(v)=p.edit().putInt("refresh_minutes",v).apply()
    var notifications:Boolean get()=p.getBoolean("notifications",false); set(v)=p.edit().putBoolean("notifications",v).apply()
}
