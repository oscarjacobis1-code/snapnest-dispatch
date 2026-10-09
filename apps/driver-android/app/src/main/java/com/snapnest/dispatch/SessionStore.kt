package com.snapnest.dispatch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    data class Session(
        val baseUrl: String,
        val accessToken: String,
        val refreshToken: String,
        val driverId: String,
        val driverName: String = "",
        val vehicle: String = ""
    )

    private val prefs = context.applicationContext.getSharedPreferences("dispatch_secure", Context.MODE_PRIVATE)
    private val alias = "snapnest_dispatch_session_v1"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$encrypted"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "Invalid encrypted session value" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    fun save(session: Session) {
        prefs.edit()
            .putString("base_url", encrypt(session.baseUrl))
            .putString("access_token", encrypt(session.accessToken))
            .putString("refresh_token", encrypt(session.refreshToken))
            .putString("driver_id", encrypt(session.driverId))
            .putString("driver_name", encrypt(session.driverName))
            .putString("vehicle", encrypt(session.vehicle))
            .apply()
    }

    fun load(): Session? = runCatching {
        val base = prefs.getString("base_url", null) ?: return null
        val access = prefs.getString("access_token", null) ?: return null
        val refresh = prefs.getString("refresh_token", null) ?: return null
        val driver = prefs.getString("driver_id", null) ?: return null
        val name = prefs.getString("driver_name", null)?.let(::decrypt).orEmpty()
        val vehicle = prefs.getString("vehicle", null)?.let(::decrypt).orEmpty()
        Session(decrypt(base), decrypt(access), decrypt(refresh), decrypt(driver), name, vehicle)
    }.getOrElse {
        clear()
        null
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
