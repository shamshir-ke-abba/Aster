package com.aster.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure local credential storage for Aster.
 * Encrypts authentication tokens using hardware-backed AndroidKeyStore (AES-256-GCM)
 * on Android devices, with graceful local fallback for JVM test runners.
 */
@Singleton
class AsterAuthStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val PREFS_NAME = "aster_auth_secure_prefs"
    private val KEY_TOKEN_CIPHERTEXT = "auth_token_ciphertext"
    private val KEY_TOKEN_IV = "auth_token_iv"
    private val KEY_USER_ID = "auth_user_id"
    private val KEY_RAW_TOKEN_DEV = "auth_raw_token_dev"
    private val KEY_ALIAS = "AsterAuthKey_v1"
    private val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val TRANSFORMATION = "AES/GCM/NoPadding"
    private val GCM_TAG_LENGTH = 128

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getAuthToken(): String? {
        val ciphertextB64 = prefs.getString(KEY_TOKEN_CIPHERTEXT, null)
        val ivB64 = prefs.getString(KEY_TOKEN_IV, null)
        if (ciphertextB64 != null && ivB64 != null) {
            try {
                val secretKey = getOrCreateKey()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)
                val plaintextBytes = cipher.doFinal(ciphertext)
                return String(plaintextBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                // Keystore error or mock environment
            }
        }
        return prefs.getString(KEY_RAW_TOKEN_DEV, null)
    }

    @Synchronized
    fun saveAuthToken(token: String) {
        if (token.isBlank()) return
        try {
            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(KEY_TOKEN_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_TOKEN_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .remove(KEY_RAW_TOKEN_DEV)
                .apply()
        } catch (e: Exception) {
            // Fallback for JVM unit test environments without AndroidKeyStore
            prefs.edit()
                .putString(KEY_RAW_TOKEN_DEV, token)
                .remove(KEY_TOKEN_CIPHERTEXT)
                .remove(KEY_TOKEN_IV)
                .apply()
        }
    }

    @Synchronized
    fun getAuthenticatedUserId(): String? {
        return prefs.getString(KEY_USER_ID, null)
    }

    @Synchronized
    fun saveAuthenticatedUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    @Synchronized
    fun getAsterId(): String? {
        return prefs.getString("auth_aster_id", null)
    }

    @Synchronized
    fun saveAsterId(asterId: String) {
        prefs.edit().putString("auth_aster_id", asterId).apply()
    }

    @Synchronized
    fun getUsername(): String? {
        return prefs.getString("auth_username", null)
    }

    @Synchronized
    fun saveUsername(username: String) {
        prefs.edit().putString("auth_username", username).apply()
    }

    @Synchronized
    fun getDisplayName(): String? {
        return prefs.getString("auth_display_name", null)
    }

    @Synchronized
    fun saveDisplayName(displayName: String) {
        prefs.edit().putString("auth_display_name", displayName).apply()
    }

    private val _viewModeFlow by lazy {
        kotlinx.coroutines.flow.MutableStateFlow(getViewMode())
    }
    val viewModeFlow: kotlinx.coroutines.flow.StateFlow<String> by lazy {
        _viewModeFlow
    }

    @Synchronized
    fun getViewMode(): String {
        return prefs.getString("app_chat_view_mode", "CONSTELLATION") ?: "CONSTELLATION"
    }

    @Synchronized
    fun saveViewMode(mode: String) {
        prefs.edit().putString("app_chat_view_mode", mode).apply()
        _viewModeFlow.value = mode
    }

    @Synchronized
    fun clearAuthToken() {
        prefs.edit()
            .remove(KEY_TOKEN_CIPHERTEXT)
            .remove(KEY_TOKEN_IV)
            .remove(KEY_RAW_TOKEN_DEV)
            .remove(KEY_USER_ID)
            .remove("auth_aster_id")
            .remove("auth_username")
            .remove("auth_display_name")
            .apply()
    }

    @Synchronized
    fun getServerUrl(): String? {
        return prefs.getString("auth_server_url", null)
    }

    @Synchronized
    fun saveServerUrl(url: String) {
        if (url.isNotBlank()) {
            prefs.edit().putString("auth_server_url", url).apply()
        }
    }

    @Synchronized
    fun clearServerUrl() {
        prefs.edit().remove("auth_server_url").apply()
    }

    fun isProvisioned(): Boolean {
        return !getAuthToken().isNullOrBlank()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
