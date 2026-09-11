package tr.borsatakip.v5.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(c: Context) {
    private val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = p.getString("base_url", "") ?: ""
        set(v) = p.edit().putString("base_url", v.trim().removeSuffix("/")).apply()

    var apiKey: String
        get() = getEncrypted("api_key_encrypted")
        set(v) = putEncrypted("api_key_encrypted", v)

    /**
     * Production backend remains the primary source. On a fresh install the experimental
     * Yahoo fallback is enabled so BIST scanning can still work when no backend is configured.
     * Yahoo data is always labelled delayed/experimental and is never presented as real-time.
     */
    var experimentalProvidersEnabled: Boolean
        get() = p.getBoolean("experimental_providers_enabled", true)
        set(v) = p.edit().putBoolean("experimental_providers_enabled", v).apply()

    /** V5.1.24 ve öncesinden kalmış TradingView kimlik/oturum kayıtlarını siler. */
    fun purgeLegacyTradingViewState() {
        p.edit()
            .remove("tv_username")
            .remove("tv_password_encrypted")
            .remove("tv_session_id_encrypted")
            .remove("tv_session_sign_encrypted")
            .remove("tv_auth_token_encrypted")
            .remove("tv_authenticated_at")
            .apply()
    }

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

    /** Yahoo'da daha önce gerçek OHLCV ile doğrulanmış semboller. */
    var yahooSupportedSymbols: Set<String>
        get() = p.getStringSet("yahoo_supported_symbols", emptySet())?.toSet().orEmpty()
        set(v) = p.edit().putStringSet("yahoo_supported_symbols", v).apply()

    /** Yahoo'nun kalıcı olarak 400/404/veri-yok döndürdüğü semboller. TTL dolunca yeniden doğrulanır. */
    var yahooUnsupportedSymbols: Set<String>
        get() = p.getStringSet("yahoo_unsupported_symbols", emptySet())?.toSet().orEmpty()
        set(v) = p.edit().putStringSet("yahoo_unsupported_symbols", v).apply()

    var yahooSymbolCacheUpdatedAt: Long
        get() = p.getLong("yahoo_symbol_cache_updated_at", 0L)
        set(v) = p.edit().putLong("yahoo_symbol_cache_updated_at", v).apply()

    fun clearYahooSymbolCompatibilityCache() {
        p.edit()
            .remove("yahoo_supported_symbols")
            .remove("yahoo_unsupported_symbols")
            .remove("yahoo_symbol_cache_updated_at")
            .apply()
    }

    private fun getEncrypted(key: String): String {
        val encrypted = p.getString(key, null)
        return if (encrypted.isNullOrBlank()) "" else decrypt(encrypted).orEmpty()
    }

    private fun putEncrypted(key: String, value: String) {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            p.edit().remove(key).apply()
            return
        }
        encrypt(normalized)?.let { p.edit().putString(key, it).apply() }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(value: String): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val KEY_ALIAS = "borsa_takip_api_key"
        private const val IV_SIZE = 12
    }
}
