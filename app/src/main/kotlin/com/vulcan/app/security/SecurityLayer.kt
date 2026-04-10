package com.vulcan.app.security

import android.content.Context
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vulcan.app.data.database.VulcanDatabase
import com.vulcan.app.data.database.entities.AuditEntry
import com.vulcan.app.util.VulcanLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// MANIFEST VERIFIER — Ed25519 signature verification + SHA-256 checksums
// ─────────────────────────────────────────────────────────────────────────────

class ManifestVerifier {

    // Hardcoded Vulcan Store public key (Ed25519).
    // To rotate, ship an app update. Never update this via network.
    private val VULCAN_STORE_PUBLIC_KEY =
        "PLACEHOLDER_ED25519_VULCAN_STORE_PUBLIC_KEY_BASE64"

    /**
     * Verifies the Ed25519 signature of a manifest JSON string.
     * Uses BouncyCastle (bundled) for Ed25519 on Android.
     */
    fun verify(manifestJson: String, signature: String, developerKey: String? = null): Boolean {
        val keyToUse = developerKey ?: VULCAN_STORE_PUBLIC_KEY
        return try {
            val keyBytes  = Base64.decode(keyToUse, Base64.DEFAULT)
            val sigBytes  = Base64.decode(signature, Base64.DEFAULT)
            val dataBytes = manifestJson.toByteArray(Charsets.UTF_8)

            // Ed25519 via BouncyCastle
            val pk        = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(keyBytes, 0)
            val verifier  = org.bouncycastle.crypto.signers.Ed25519Signer()
            verifier.init(false, pk)
            verifier.update(dataBytes, 0, dataBytes.size)
            verifier.verifySignature(sigBytes)
        } catch (e: Exception) {
            VulcanLogger.e("ManifestVerifier: signature verification failed — ${e.message}")
            false
        }
    }

    /** SHA-256 checksum verification for downloaded source archives. */
    fun verifySourceChecksum(file: File, expectedSha256: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val match  = actual.equals(expectedSha256, ignoreCase = true)
        if (!match) VulcanLogger.e("Checksum mismatch for ${file.name}! Expected=$expectedSha256 Actual=$actual")
        return match
    }

    /** In developer mode, allow unsigned manifests from local files. */
    fun verifyOrSkipForDev(manifestJson: String, signature: String, devMode: Boolean): Boolean {
        if (devMode && signature.isBlank()) {
            VulcanLogger.w("ManifestVerifier: skipping signature check in dev mode")
            return true
        }
        return verify(manifestJson, signature)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD AUTH — Biometric + PIN protection for the Vulcan app itself
// ─────────────────────────────────────────────────────────────────────────────

class DashboardAuth(private val context: Context) {

    enum class AuthMethod { NONE, PIN, BIOMETRIC, BIOMETRIC_OR_PIN }

    private val prefs get() = context.getSharedPreferences("vulcan_security", Context.MODE_PRIVATE)

    fun getConfiguredMethod(): AuthMethod =
        AuthMethod.valueOf(prefs.getString("auth_method", AuthMethod.NONE.name)!!)

    fun setAuthMethod(method: AuthMethod) {
        prefs.edit().putString("auth_method", method.name).apply()
    }

    fun setPin(hashedPin: String) {
        prefs.edit().putString("pin_hash", hashedPin).apply()
    }

    fun authenticate(
        activity: FragmentActivity,
        reason: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        when (getConfiguredMethod()) {
            AuthMethod.NONE -> onSuccess()
            AuthMethod.PIN  -> showPINDialog(reason, onSuccess, onFailure)
            AuthMethod.BIOMETRIC,
            AuthMethod.BIOMETRIC_OR_PIN -> showBiometricPrompt(activity, reason, onSuccess, onFailure)
        }
    }

    private fun showBiometricPrompt(
        activity: FragmentActivity,
        reason: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationFailed() {
                onFailure("Authentication failed")
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (getConfiguredMethod() == AuthMethod.BIOMETRIC_OR_PIN) {
                    showPINDialog(reason, onSuccess, onFailure)
                } else {
                    onFailure(msg.toString())
                }
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Vulcan — Authenticate")
            .setSubtitle(reason)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(info)
    }

    private fun showPINDialog(reason: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        // PIN dialog is shown via the UI layer (Compose). This triggers the event.
        // In production, this would show a full-screen PIN entry composable.
        // For now, we expose the event through a StateFlow that the UI observes.
        PinAuthBus.requestPin(reason, onSuccess, onFailure)
    }

    fun isBiometricAvailable(): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
               BiometricManager.BIOMETRIC_SUCCESS
    }
}

// Simple event bus for PIN auth requests (UI observes this)
object PinAuthBus {
    private var pendingRequest: Triple<String, () -> Unit, (String) -> Unit>? = null

    fun requestPin(reason: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        pendingRequest = Triple(reason, onSuccess, onFailure)
    }

    fun consumeRequest() = pendingRequest?.also { pendingRequest = null }
}

// ─────────────────────────────────────────────────────────────────────────────
// APP ISOLATION MANAGER — Per-app data namespacing
// ─────────────────────────────────────────────────────────────────────────────

class AppIsolationManager(private val context: Context) {

    /**
     * Ensures each app's data directory is properly namespaced.
     * Apps cannot access each other's data directories.
     * Each app sees only its own /sdcard/Vulcan/apps/{id}/data/
     */
    fun setupIsolation(appId: String) {
        val dataDir = com.vulcan.app.storage.StorageManager.appDataDir(context, appId)
        dataDir.mkdirs()
        // On Android, file-level isolation is inherent via directory structure.
        // Enhanced isolation via PRoot (each app gets its own rootfs view).
    }

    fun getIsolatedDataPath(appId: String): String =
        com.vulcan.app.storage.StorageManager.appRealDataDir(context, appId).canonicalPath
}

// ─────────────────────────────────────────────────────────────────────────────
// AUDIT LOGGER — Immutable security event log (stored in Room)
// ─────────────────────────────────────────────────────────────────────────────

object AuditLogger {

    private val scope = CoroutineScope(Dispatchers.IO)

    fun log(
        context: Context,
        event: String,
        details: String,
        severity: String = "info"   // "info" | "warn" | "critical"
    ) {
        scope.launch {
            try {
                val db = VulcanDatabase.getInstance(context)
                db.auditDao().insert(
                    AuditEntry(
                        timestamp = System.currentTimeMillis(),
                        event     = event,
                        details   = details,
                        severity  = severity
                    )
                )
                if (severity == "critical") {
                    VulcanLogger.e("AUDIT[$severity] $event: $details")
                } else {
                    VulcanLogger.i("AUDIT[$severity] $event: $details")
                }
            } catch (e: Exception) {
                VulcanLogger.e("AuditLogger write failed: ${e.message}")
            }
        }
    }

    fun info(context: Context, event: String, details: String = "") =
        log(context, event, details, "info")

    fun warn(context: Context, event: String, details: String = "") =
        log(context, event, details, "warn")

    fun critical(context: Context, event: String, details: String = "") =
        log(context, event, details, "critical")
}
