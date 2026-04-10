package com.vulcan.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vulcan.app.storage.StorageManager
import com.vulcan.app.util.VulcanLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SECRETS VAULT — AES-256-GCM encryption via Android Keystore.
 *
 * API keys, passwords, tokens NEVER touch disk in plaintext.
 * The .env file holds non-sensitive config only.
 * Vault encrypts with a hardware-backed key (Keystore) — survives app updates.
 *
 * Storage format: [12-byte IV] + [AES-256-GCM ciphertext]
 * Key is generated once, stored in Android Keystore, never leaves secure hardware.
 */
class SecretsVault(private val context: Context) {

    private val KEY_ALIAS = "vulcan_secrets_master_key_v2"
    private val GCM_TAG_LENGTH = 128
    private val gson = Gson()

    init {
        ensureKeyExists()
        StorageManager.vaultDir(context).mkdirs()
    }

    // ─── PUBLIC API ───────────────────────────────────────────────────────────

    fun putSecret(appId: String, key: String, value: String) {
        val all = getAllSecrets()
        all.getOrPut(appId) { mutableMapOf() }[key] = value
        persistEncrypted(all)
        VulcanLogger.i("SecretsVault: stored $key for $appId")
    }

    fun getSecret(appId: String, key: String): String? =
        getAllSecrets()[appId]?.get(key)

    fun getAppSecrets(appId: String): Map<String, String> =
        getAllSecrets()[appId]?.toMap() ?: emptyMap()

    fun deleteSecret(appId: String, key: String) {
        val all = getAllSecrets()
        all[appId]?.remove(key)
        persistEncrypted(all)
    }

    fun deleteAppSecrets(appId: String) {
        val all = getAllSecrets()
        all.remove(appId)
        persistEncrypted(all)
        VulcanLogger.i("SecretsVault: cleared all secrets for $appId")
    }

    fun hasSecret(appId: String, key: String): Boolean =
        getAllSecrets()[appId]?.containsKey(key) == true

    fun listKeys(appId: String): List<String> =
        getAllSecrets()[appId]?.keys?.toList() ?: emptyList()

    /**
     * Builds the full env map for an app:
     * plain .env values merged with vault secrets.
     * Vault secrets take precedence (they override plain env).
     */
    fun buildFullEnv(appId: String, plainEnv: Map<String, String>): Map<String, String> =
        plainEnv + getAppSecrets(appId)

    // ─── CRYPTO ───────────────────────────────────────────────────────────────

    private fun ensureKeyExists() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)   // Auto-unlocks, no lockscreen required
                    .setInvalidatedByBiometricEnrollment(false)  // Survives biometric changes
                    .build()
            )
            keyGen.generateKey()
            VulcanLogger.i("SecretsVault: master key generated in Android Keystore")
        }
    }

    private fun getKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun getAllSecrets(): MutableMap<String, MutableMap<String, String>> {
        val vaultFile = StorageManager.vaultFile(context)
        if (!vaultFile.exists() || vaultFile.length() == 0L) return mutableMapOf()

        return try {
            val data       = vaultFile.readBytes()
            val iv         = data.copyOf(12)
            val ciphertext = data.copyOfRange(12, data.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val plaintext = cipher.doFinal(ciphertext)

            val type = object : TypeToken<MutableMap<String, MutableMap<String, String>>>() {}.type
            gson.fromJson(String(plaintext, Charsets.UTF_8), type) ?: mutableMapOf()
        } catch (e: Exception) {
            VulcanLogger.e("SecretsVault: decryption failed — ${e.message}")
            mutableMapOf()
        }
    }

    private fun persistEncrypted(data: Map<String, Map<String, String>>) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getKey())

            val plaintext  = gson.toJson(data).toByteArray(Charsets.UTF_8)
            val ciphertext = cipher.doFinal(plaintext)
            val iv         = cipher.iv     // 12 bytes, auto-generated per encryption

            StorageManager.vaultFile(context).writeBytes(iv + ciphertext)
        } catch (e: Exception) {
            VulcanLogger.e("SecretsVault: encryption failed — ${e.message}")
            throw RuntimeException("Failed to persist vault: ${e.message}", e)
        }
    }
}
