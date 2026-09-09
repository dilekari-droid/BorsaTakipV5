package tr.borsatakip.v5.data

import android.content.Context

class SettingsStore(c: Context) {
    private val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = p.getString("base_url", "") ?: ""
        set(v) = p.edit().putString("base_url", v.trim().removeSuffix("/")).apply()

    var apiKey: String
        get() = p.getString("api_key", "") ?: ""
        set(v) = p.edit().putString("api_key", v.trim()).apply()

    var refreshMinutes: Int
        get() = p.getInt("refresh_minutes", 60)
        set(v) = p.edit().putInt("refresh_minutes", v).apply()

    var notifications: Boolean
        get() = p.getBoolean("notifications", false)
        set(v) = p.edit().putBoolean("notifications", v).apply()

    var yahooFallbackEnabled: Boolean
        get() = p.getBoolean("yahoo_fallback_enabled", true)
        set(v) = p.edit().putBoolean("yahoo_fallback_enabled", v).apply()

    var lastProviderId: String
        get() = p.getString("last_provider_id", "none") ?: "none"
        set(v) = p.edit().putString("last_provider_id", v).apply()

    var lastProviderLabel: String
        get() = p.getString("last_provider_label", "Veri alınmadı") ?: "Veri alınmadı"
        set(v) = p.edit().putString("last_provider_label", v).apply()

    var lastProviderTimestamp: Long
        get() = p.getLong("last_provider_timestamp", 0L)
        set(v) = p.edit().putLong("last_provider_timestamp", v).apply()

    var cachedBistSymbols: Set<String>
        get() = p.getStringSet("cached_bist_symbols", emptySet())?.toSet().orEmpty()
        set(v) = p.edit().putStringSet("cached_bist_symbols", v).apply()
}
