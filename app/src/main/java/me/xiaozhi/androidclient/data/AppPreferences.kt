package me.xiaozhi.androidclient.data

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

private const val PREFS_NAME = "xiaozhi_android_client"
private const val KEY_OTA_URL = "ota_url"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_CLIENT_ID = "client_id"
private const val KEY_ASSISTANT_AVATAR_PATH = "assistant_avatar_path"
private const val KEY_IDLE_VIDEO_PATH = "idle_video_path"
private const val KEY_GREETING_VIDEO_PATH = "greeting_video_path"
private const val KEY_LISTENING_VIDEO_PATH = "listening_video_path"
private const val KEY_SPEAKING_VIDEO_PATH = "speaking_video_path"
private const val KEY_WEBSOCKET_URL = "websocket_url"
private const val KEY_AUTH_TOKEN = "auth_token"
private const val KEY_PROTOCOL_VERSION = "protocol_version"
private const val KEY_MCP_PAYLOAD = "mcp_payload"
private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
private const val KEY_WAKE_WORDS = "wake_words"
private const val KEY_PRIMARY_ROLE_NAME = "primary_role_name"
private const val KEY_ACTIVE_ROLE_ID = "active_role_id"
private const val KEY_TERMUX_ENABLED = "termux_enabled"
private const val KEY_PYTHON_PATH = "python_path"
private const val KEY_PYTHON_SCRIPT_PATH = "python_script_path"
private const val KEY_PYTHON_WORKDIR = "python_workdir"
private const val KEY_TERMUX_API_COMMAND = "termux_api_command"
private const val KEY_TERMUX_API_ARGUMENTS = "termux_api_arguments"
private const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
private const val KEY_DEBUG_WAV_DUMP_ENABLED = "debug_wav_dump_enabled"

const val DEFAULT_OTA_URL: String = "https://api.tenclass.net/xiaozhi/ota/"
const val DEFAULT_PROTOCOL_VERSION: String = "1"
const val DEFAULT_MCP_PAYLOAD: String =
    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"
const val DEFAULT_WAKE_WORDS: String = "小智小智, 你好小智"
const val DEFAULT_PYTHON_PATH: String = "/data/data/com.termux/files/usr/bin/python"
const val DEFAULT_TERMUX_API_COMMAND: String = "/data/data/com.termux/files/usr/bin/termux-battery-status"

data class StoredConfig(
    val otaUrl: String,
    val deviceId: String,
    val clientId: String,
    val assistantAvatarPath: String,
    val idleVideoPath: String,
    val greetingVideoPath: String,
    val listeningVideoPath: String,
    val speakingVideoPath: String,
    val websocketUrl: String,
    val authToken: String,
    val protocolVersion: String,
    val mcpPayload: String,
    val wakeWordEnabled: Boolean,
    val wakeWords: String,
    val primaryRoleName: String,
    val activeRoleId: String,
    val termuxEnabled: Boolean,
    val pythonPath: String,
    val pythonScriptPath: String,
    val pythonWorkdir: String,
    val termuxApiCommand: String,
    val termuxApiArguments: String,
    val debugLoggingEnabled: Boolean,
    val debugWavDumpEnabled: Boolean,
)

class AppPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): StoredConfig {
        val existingDeviceId = prefs.getString(KEY_DEVICE_ID, null)
        val deviceId = normalizeDeviceId(existingDeviceId)
        if (existingDeviceId != deviceId) {
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        val clientId = prefs.getString(KEY_CLIENT_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_CLIENT_ID, it).apply() }

        return StoredConfig(
            otaUrl = prefs.getString(KEY_OTA_URL, DEFAULT_OTA_URL) ?: DEFAULT_OTA_URL,
            deviceId = deviceId,
            clientId = clientId,
            assistantAvatarPath = prefs.getString(KEY_ASSISTANT_AVATAR_PATH, "") ?: "",
            idleVideoPath = prefs.getString(KEY_IDLE_VIDEO_PATH, "") ?: "",
            greetingVideoPath = prefs.getString(KEY_GREETING_VIDEO_PATH, "") ?: "",
            listeningVideoPath = prefs.getString(KEY_LISTENING_VIDEO_PATH, "") ?: "",
            speakingVideoPath = prefs.getString(KEY_SPEAKING_VIDEO_PATH, "") ?: "",
            websocketUrl = prefs.getString(KEY_WEBSOCKET_URL, "") ?: "",
            authToken = prefs.getString(KEY_AUTH_TOKEN, "") ?: "",
            protocolVersion = prefs.getString(
                KEY_PROTOCOL_VERSION,
                DEFAULT_PROTOCOL_VERSION,
            ) ?: DEFAULT_PROTOCOL_VERSION,
            mcpPayload = prefs.getString(KEY_MCP_PAYLOAD, DEFAULT_MCP_PAYLOAD) ?: DEFAULT_MCP_PAYLOAD,
            wakeWordEnabled = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false),
            wakeWords = prefs.getString(KEY_WAKE_WORDS, DEFAULT_WAKE_WORDS) ?: DEFAULT_WAKE_WORDS,
            primaryRoleName = prefs.getString(KEY_PRIMARY_ROLE_NAME, "小智") ?: "小智",
            activeRoleId = prefs.getString(KEY_ACTIVE_ROLE_ID, "xiaozhi") ?: "xiaozhi",
            termuxEnabled = prefs.getBoolean(KEY_TERMUX_ENABLED, false),
            pythonPath = prefs.getString(KEY_PYTHON_PATH, DEFAULT_PYTHON_PATH) ?: DEFAULT_PYTHON_PATH,
            pythonScriptPath = prefs.getString(KEY_PYTHON_SCRIPT_PATH, "") ?: "",
            pythonWorkdir = prefs.getString(KEY_PYTHON_WORKDIR, "") ?: "",
            termuxApiCommand = prefs.getString(KEY_TERMUX_API_COMMAND, DEFAULT_TERMUX_API_COMMAND)
                ?: DEFAULT_TERMUX_API_COMMAND,
            termuxApiArguments = prefs.getString(KEY_TERMUX_API_ARGUMENTS, "") ?: "",
            debugLoggingEnabled = prefs.getBoolean(KEY_DEBUG_LOGGING_ENABLED, false),
            debugWavDumpEnabled = prefs.getBoolean(KEY_DEBUG_WAV_DUMP_ENABLED, false),
        )
    }

    fun save(config: StoredConfig) {
        prefs.edit()
            .putString(KEY_OTA_URL, config.otaUrl)
            .putString(KEY_DEVICE_ID, config.deviceId)
            .putString(KEY_CLIENT_ID, config.clientId)
            .putString(KEY_ASSISTANT_AVATAR_PATH, config.assistantAvatarPath)
            .putString(KEY_IDLE_VIDEO_PATH, config.idleVideoPath)
            .putString(KEY_GREETING_VIDEO_PATH, config.greetingVideoPath)
            .putString(KEY_LISTENING_VIDEO_PATH, config.listeningVideoPath)
            .putString(KEY_SPEAKING_VIDEO_PATH, config.speakingVideoPath)
            .putString(KEY_WEBSOCKET_URL, config.websocketUrl)
            .putString(KEY_AUTH_TOKEN, config.authToken)
            .putString(KEY_PROTOCOL_VERSION, config.protocolVersion)
            .putString(KEY_MCP_PAYLOAD, config.mcpPayload)
            .putBoolean(KEY_WAKE_WORD_ENABLED, config.wakeWordEnabled)
            .putString(KEY_WAKE_WORDS, config.wakeWords)
            .putString(KEY_PRIMARY_ROLE_NAME, config.primaryRoleName)
            .putString(KEY_ACTIVE_ROLE_ID, config.activeRoleId)
            .putBoolean(KEY_TERMUX_ENABLED, config.termuxEnabled)
            .putString(KEY_PYTHON_PATH, config.pythonPath)
            .putString(KEY_PYTHON_SCRIPT_PATH, config.pythonScriptPath)
            .putString(KEY_PYTHON_WORKDIR, config.pythonWorkdir)
            .putString(KEY_TERMUX_API_COMMAND, config.termuxApiCommand)
            .putString(KEY_TERMUX_API_ARGUMENTS, config.termuxApiArguments)
            .putBoolean(KEY_DEBUG_LOGGING_ENABLED, config.debugLoggingEnabled)
            .putBoolean(KEY_DEBUG_WAV_DUMP_ENABLED, config.debugWavDumpEnabled)
            .apply()
    }

    private fun normalizeDeviceId(rawValue: String?): String {
        val normalized = rawValue
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace("-", ":")
            ?.takeIf(::isValidMacAddress)
        if (normalized != null) {
            return normalized
        }

        val compactHex = rawValue
            ?.filter { it.isLetterOrDigit() }
            ?.lowercase(Locale.US)
            ?.takeIf { it.length >= 12 }
            ?.takeLast(12)
        if (compactHex != null && compactHex.all { it in '0'..'9' || it in 'a'..'f' }) {
            return compactHex.chunked(2).joinToString(":")
        }

        val seed = rawValue
            ?.takeIf { it.isNotBlank() }
            ?: Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            ).orEmpty().ifBlank { UUID.randomUUID().toString() }

        return pseudoMacFromSeed(seed)
    }

    private fun isValidMacAddress(value: String): Boolean {
        return Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$").matches(value)
    }

    private fun pseudoMacFromSeed(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val bytes = digest.copyOfRange(0, 6)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        return bytes.joinToString(":") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
    }
}
