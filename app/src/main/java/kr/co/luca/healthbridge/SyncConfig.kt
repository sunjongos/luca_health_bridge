package kr.co.luca.healthbridge

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SyncConfig {
    private const val PREFS = "health_sync_secure_v5"
    private const val KEY_TOKEN = "upload_token"
    val ENDPOINT: String = BuildConfig.HEALTH_ENDPOINT

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "") ?: ""

    fun saveToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }
}
