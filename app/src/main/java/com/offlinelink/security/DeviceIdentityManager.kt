package com.offlinelink.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val publicKeyHex: String,
    val fingerprint: String
)

class DeviceIdentityManager(private val context: Context) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("offlinelink_identity_prefs", Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedIdentity: DeviceIdentity? = null

    fun getIdentity(): DeviceIdentity {
        cachedIdentity?.let { return it }

        synchronized(this) {
            cachedIdentity?.let { return it }

            var deviceId = prefs.getString(KEY_DEVICE_ID, null)
            var deviceName = prefs.getString(KEY_DEVICE_NAME, null)
            var publicKeyHex = prefs.getString(KEY_PUBLIC_KEY, null)

            if (deviceId == null || publicKeyHex == null) {
                // First launch: generate cryptographic identity
                val randomBytes = CryptoEngine.generateRandomBytes(32)
                publicKeyHex = randomBytes.joinToString("") { "%02X".format(it) }
                deviceId = "NODE-" + publicKeyHex.take(8)

                val defaultName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
                deviceName = defaultName

                prefs.edit()
                    .putString(KEY_DEVICE_ID, deviceId)
                    .putString(KEY_DEVICE_NAME, deviceName)
                    .putString(KEY_PUBLIC_KEY, publicKeyHex)
                    .apply()
            }

            val rawHash = CryptoEngine.computeSha256Hex(publicKeyHex.toByteArray(Charsets.UTF_8))
            val fingerprint = CryptoEngine.formatFingerprint(rawHash)

            val identity = DeviceIdentity(
                deviceId = deviceId,
                deviceName = deviceName ?: "OfflineLink Node",
                publicKeyHex = publicKeyHex,
                fingerprint = fingerprint
            )
            cachedIdentity = identity
            return identity
        }
    }

    fun updateDeviceName(newName: String) {
        val trimmed = newName.trim().take(32)
        if (trimmed.isNotBlank()) {
            prefs.edit().putString(KEY_DEVICE_NAME, trimmed).apply()
            cachedIdentity = null
        }
    }

    fun clearIdentity() {
        prefs.edit().clear().apply()
        cachedIdentity = null
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PUBLIC_KEY = "public_key_hex"

        @Volatile
        private var instance: DeviceIdentityManager? = null

        fun getInstance(context: Context): DeviceIdentityManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceIdentityManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
