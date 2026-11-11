package com.pocketupwatch

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class SecretStore {
    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("pocket-upwatch-headers", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pocket-upwatch-headers", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object { private val KEY_LOCK = Any() }
    @Synchronized fun encrypt(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(Gson().toJson(headers).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    @Synchronized fun decrypt(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28) { "Invalid encrypted credentials" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return Gson().fromJson(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), object : TypeToken<Map<String, String>>() {}.type)
    }
}

internal fun displayTarget(value: String): String {
    val url = value.toHttpUrlOrNull() ?: return value
    return url.newBuilder().username("").password("").query(null).fragment(null).build().toString()
}
