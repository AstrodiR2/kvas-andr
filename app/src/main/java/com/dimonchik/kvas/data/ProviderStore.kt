package com.dimonchik.kvas.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Keeps provider API keys in Android Keystore-backed encrypted preferences. */
class ProviderStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "kvas_providers",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun all(): List<ProviderConfig> = runCatching {
        val json = JSONArray(prefs.getString("providers", "[]"))
        buildList {
            for (i in 0 until json.length()) {
                val p = json.getJSONObject(i)
                val models = p.optJSONArray("models") ?: JSONArray()
                add(ProviderConfig(
                    p.getString("id"), p.getString("name"), p.getString("baseUrl"), p.getString("apiKey"),
                    buildList {
                        for (j in 0 until models.length()) {
                            val m = models.getJSONObject(j)
                            add(ModelInfo(m.getString("id"), m.optLong("contextLimit").takeIf { it > 0 }))
                        }
                    }
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun save(provider: ProviderConfig) {
        val existing = all().filterNot { it.id == provider.id }
        val rows = JSONArray()
        (existing + provider).forEach { p ->
            rows.put(JSONObject().put("id", p.id).put("name", p.name).put("baseUrl", p.baseUrl)
                .put("apiKey", p.apiKey).put("models", JSONArray().apply {
                    p.models.forEach { put(JSONObject().put("id", it.id).put("contextLimit", it.contextLimit ?: 0)) }
                }))
        }
        prefs.edit().putString("providers", rows.toString()).apply()
    }
}
