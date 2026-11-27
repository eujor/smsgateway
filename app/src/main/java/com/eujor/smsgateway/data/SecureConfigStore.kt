package com.eujor.smsgateway.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppConfig(
    val serverUrl: String = "",
    val apiKey: String = "",
    val deviceId: String = UUID.randomUUID().toString(),
    val deviceName: String = "Android SIM Gateway"
)

object SecureConfigStore {
    private const val PREFS = "eujor_secure_config"
    private const val VALUE = "encrypted_config"
    private const val KEY_ALIAS = "eujor_smsgateway_config_v1"

    fun load(context: Context): AppConfig {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(VALUE, null)
            ?: return AppConfig()
        return try {
            val packed = Base64.decode(encoded, Base64.DEFAULT)
            require(packed.size > 12) { "Encrypted configuration is incomplete." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
            AppConfig(
                serverUrl = json.optString("server_url"),
                apiKey = json.optString("api_key"),
                deviceId = json.optString("device_id").ifBlank { UUID.randomUUID().toString() },
                deviceName = json.optString("device_name").ifBlank { "Android SIM Gateway" }
            )
        } catch (_: Exception) {
            AppConfig()
        }
    }

    fun save(context: Context, config: AppConfig) {
        val json = JSONObject()
            .put("server_url", config.serverUrl.trim())
            .put("api_key", config.apiKey)
            .put("device_id", config.deviceId.trim())
            .put("device_name", config.deviceName.trim())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
