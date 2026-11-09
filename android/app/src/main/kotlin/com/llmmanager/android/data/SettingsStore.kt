package com.llmmanager.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settings: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Server address (plain) and access token (AES-256-GCM under a non-exportable
 * Android Keystore key; only the IV and ciphertext reach DataStore).
 */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settings

    val serverUrl: Flow<String?> = store.data.map { it[SERVER_URL] }
    val hasToken: Flow<Boolean> = store.data.map { it[TOKEN_CIPHERTEXT] != null }

    suspend fun setServerUrl(url: String) {
        store.edit { it[SERVER_URL] = url }
    }

    suspend fun saveToken(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        store.edit {
            it[TOKEN_IV] = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            it[TOKEN_CIPHERTEXT] = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }
    }

    /** The token, or null when none is saved or it can no longer be decrypted (then it is cleared). */
    suspend fun token(): String? {
        val prefs = store.data.first()
        val iv = prefs[TOKEN_IV]
        val ciphertext = prefs[TOKEN_CIPHERTEXT]
        if (iv == null || ciphertext == null) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            }
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            clearToken()
            null
        } catch (_: IllegalArgumentException) {
            clearToken()
            null
        }
    }

    suspend fun clearToken() {
        store.edit {
            it.remove(TOKEN_IV)
            it.remove(TOKEN_CIPHERTEXT)
        }
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.takeIf { it.containsAlias(KEY_ALIAS) }?.deleteEntry(KEY_ALIAS)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "llm-manager.api-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_BITS = 256
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN_IV = stringPreferencesKey("token_iv")
        val TOKEN_CIPHERTEXT = stringPreferencesKey("token_ciphertext")
    }
}
