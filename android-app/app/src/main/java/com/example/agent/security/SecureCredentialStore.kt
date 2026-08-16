package com.example.agent.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Metadata and the decrypted device credential for an enrolled gateway. */
data class StoredDeviceCredential(
    val deviceId: String,
    val gatewayBaseUrl: String,
    val deviceSecret: String,
)

class CredentialProtectionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

interface DeviceCredentialStore {
    fun hasEnrollment(): Boolean
    fun saveNewEnrollment(deviceId: String, gatewayBaseUrl: String, deviceSecret: String)
    fun load(): StoredDeviceCredential?
    fun clearEnrollment()
}

/**
 * Protects the long-lived gateway device credential with a non-exportable
 * Android Keystore AES key. Only ciphertext, IV, and non-secret routing metadata
 * are stored in SharedPreferences. A hardware-backed key is requested when the
 * device supports StrongBox and falls back to the Android Keystore otherwise.
 */
class SecureCredentialStore(context: Context) : DeviceCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun hasEnrollment(): Boolean =
        preferences.contains(KEY_DEVICE_ID) &&
            preferences.contains(KEY_GATEWAY_BASE_URL) &&
            preferences.contains(KEY_SECRET_CIPHERTEXT) &&
            preferences.contains(KEY_SECRET_IV)

    @Synchronized
    override fun saveNewEnrollment(deviceId: String, gatewayBaseUrl: String, deviceSecret: String) {
        require(DEVICE_ID_PATTERN.matches(deviceId)) { "Invalid device identifier" }
        require(deviceSecret.length in 32..256) { "Invalid device credential" }
        check(!hasEnrollment()) { "This installation is already enrolled" }

        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData(deviceId, gatewayBaseUrl))
        val ciphertext = cipher.doFinal(deviceSecret.toByteArray(StandardCharsets.UTF_8))

        val committed = preferences.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_GATEWAY_BASE_URL, gatewayBaseUrl)
            .putString(KEY_SECRET_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_SECRET_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        if (!committed) {
            throw CredentialProtectionException("Could not persist the protected credential")
        }
    }

    @Synchronized
    override fun load(): StoredDeviceCredential? {
        if (!hasEnrollment()) return null
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        val gatewayBaseUrl = preferences.getString(KEY_GATEWAY_BASE_URL, null) ?: return null
        val encodedIv = preferences.getString(KEY_SECRET_IV, null) ?: return null
        val encodedCiphertext = preferences.getString(KEY_SECRET_CIPHERTEXT, null) ?: return null

        try {
            val key = existingKey()
                ?: throw CredentialProtectionException("Credential-protection key is unavailable")
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            cipher.updateAAD(associatedData(deviceId, gatewayBaseUrl))
            val plaintext = cipher.doFinal(
                Base64.decode(encodedCiphertext, Base64.NO_WRAP),
            )
            val secret = plaintext.toString(StandardCharsets.UTF_8)
            if (!DEVICE_ID_PATTERN.matches(deviceId) || secret.length !in 32..256) {
                throw CredentialProtectionException("Protected credential is invalid")
            }
            return StoredDeviceCredential(deviceId, gatewayBaseUrl, secret)
        } catch (error: CredentialProtectionException) {
            throw error
        } catch (error: AEADBadTagException) {
            throw CredentialProtectionException("Protected credential could not be authenticated", error)
        } catch (error: Exception) {
            throw CredentialProtectionException("Protected credential could not be decrypted", error)
        }
    }

    /** Explicit operator action; deleting enrollment also deletes its Keystore key. */
    @Synchronized
    override fun clearEnrollment() {
        if (!preferences.edit().clear().commit()) {
            throw CredentialProtectionException("Could not clear enrollment metadata")
        }
        val keyStore = keyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    fun isHardwareBacked(): Boolean {
        val key = existingKey() ?: return false
        return try {
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            factory.getKeySpec(key, KeyInfo::class.java).isInsideSecureHardware
        } catch (_: Exception) {
            false
        }
    }

    private fun existingKey(): SecretKey? =
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKey(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // Hardware capability is device-specific; use Android Keystore fallback.
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
            if (strongBox) builder.setIsStrongBoxBacked(true)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun associatedData(deviceId: String, gatewayBaseUrl: String): ByteArray =
        "$deviceId\n$gatewayBaseUrl".toByteArray(StandardCharsets.UTF_8)

    companion object {
        private const val PREFERENCES_NAME = "gateway_enrollment_v1"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_GATEWAY_BASE_URL = "gateway_base_url"
        private const val KEY_SECRET_IV = "device_secret_iv"
        private const val KEY_SECRET_CIPHERTEXT = "device_secret_ciphertext"
        private const val KEY_ALIAS = "gateway-device-credential-v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
    }
}
