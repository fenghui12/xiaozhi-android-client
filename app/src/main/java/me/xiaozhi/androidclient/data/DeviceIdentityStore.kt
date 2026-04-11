package me.xiaozhi.androidclient.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

private const val IDENTITY_PREFS_NAME = "xiaozhi_android_identity"
private const val KEY_SERIAL_NUMBER = "serial_number"
private const val KEY_HMAC_KEY = "hmac_key"
private const val KEY_ACTIVATED = "activated"
private const val KEY_DEVICE_ID = "device_id"

data class DeviceIdentity(
    val serialNumber: String,
    val hmacKey: String,
    val activated: Boolean,
)

class DeviceIdentityStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(IDENTITY_PREFS_NAME, Context.MODE_PRIVATE)

    fun loadOrCreate(deviceId: String): DeviceIdentity {
        val existingSerial = prefs.getString(KEY_SERIAL_NUMBER, null)
        val existingHmac = prefs.getString(KEY_HMAC_KEY, null)
        val existingDeviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (
            !existingSerial.isNullOrBlank() &&
            !existingHmac.isNullOrBlank() &&
            existingDeviceId == deviceId
        ) {
            return DeviceIdentity(
                serialNumber = existingSerial,
                hmacKey = existingHmac,
                activated = prefs.getBoolean(KEY_ACTIVATED, false),
            )
        }

        val identity = createIdentity(deviceId)
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_SERIAL_NUMBER, identity.serialNumber)
            .putString(KEY_HMAC_KEY, identity.hmacKey)
            .putBoolean(KEY_ACTIVATED, identity.activated)
            .apply()
        return identity
    }

    fun setActivated(value: Boolean) {
        prefs.edit().putBoolean(KEY_ACTIVATED, value).apply()
    }

    private fun createIdentity(deviceId: String): DeviceIdentity {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        val fingerprint = listOf(
            androidId,
            deviceId,
            Build.BRAND.orEmpty(),
            Build.MANUFACTURER.orEmpty(),
            Build.MODEL.orEmpty(),
            Build.DEVICE.orEmpty(),
            Build.PRODUCT.orEmpty(),
            Build.HARDWARE.orEmpty(),
            Build.FINGERPRINT.orEmpty(),
        ).joinToString(separator = "|")

        val hash = sha256Hex(fingerprint)
        val serialSuffix = deviceId
            .filter(Char::isLetterOrDigit)
            .takeLast(12)
            .ifBlank { hash.takeLast(12) }
            .lowercase(Locale.US)

        return DeviceIdentity(
            serialNumber = "SN-${hash.take(8).uppercase(Locale.US)}-$serialSuffix",
            hmacKey = sha256Hex("xiaozhi-android|$fingerprint"),
            activated = false,
        )
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(Locale.US, it.toInt() and 0xFF)) }
        }
    }
}
