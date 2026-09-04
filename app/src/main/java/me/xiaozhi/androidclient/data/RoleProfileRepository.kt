package me.xiaozhi.androidclient.data

import android.content.Context
import java.io.File
import me.xiaozhi.androidclient.model.RoleProfile
import org.json.JSONArray
import org.json.JSONObject

data class RoleProfileLoadResult(
    val profiles: List<RoleProfile>,
    val warning: String? = null,
)

class RoleProfileRepository(private val context: Context) {
    fun loadAdditionalProfiles(): RoleProfileLoadResult {
        val file = writableConfigFile()
        if (!file.exists()) {
            migrateLegacyConfig(file)
        }
        if (!file.exists()) {
            return RoleProfileLoadResult(emptyList())
        }
        return runCatching {
            val entries = JSONObject(file.readText()).optJSONArray("roles") ?: JSONArray()
            val profiles = buildList {
                for (index in 0 until entries.length()) {
                    entries.optJSONObject(index)?.toRoleProfile()?.let(::add)
                }
            }.distinctBy(RoleProfile::id)
            RoleProfileLoadResult(profiles)
        }.getOrElse { error ->
            RoleProfileLoadResult(emptyList(), "角色配置读取失败：${error.message.orEmpty()}")
        }
    }

    fun saveAdditionalProfiles(profiles: List<RoleProfile>) {
        val roles = JSONArray()
        profiles.distinctBy(RoleProfile::id).forEach { profile ->
            roles.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("displayName", profile.displayName)
                    .put("deviceId", profile.deviceId)
                    .put("clientId", profile.clientId)
                    .put("wakeWords", JSONArray(profile.wakeWords))
                    .put("avatarPath", profile.avatarPath)
                    .put("isBound", profile.isBound)
                    .put("bindingCode", profile.bindingCode)
                    .put("idleVideoPath", profile.idleVideoPath)
                    .put("greetingVideoPath", profile.greetingVideoPath)
                    .put("listeningVideoPath", profile.listeningVideoPath)
                    .put("speakingVideoPath", profile.speakingVideoPath),
            )
        }
        val file = writableConfigFile()
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(JSONObject().put("roles", roles).toString(2))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    fun configPath(): String = writableConfigFile().absolutePath

    private fun writableConfigFile(): File = File(context.filesDir, ROLE_CONFIG_FILE_NAME)

    private fun migrateLegacyConfig(target: File) {
        val legacy = File(context.getExternalFilesDir(null), ROLE_CONFIG_FILE_NAME)
        if (!legacy.exists()) return
        runCatching {
            target.parentFile?.mkdirs()
            legacy.copyTo(target, overwrite = false)
        }
    }

    private fun JSONObject.toRoleProfile(): RoleProfile? {
        val id = optString("id").trim().lowercase()
        val displayName = optString("displayName").trim()
        val deviceId = optString("deviceId").trim()
        val clientId = optString("clientId").trim()
        val wakeWords = optJSONArray("wakeWords")?.toStringList().orEmpty()
        if (id.isBlank() || displayName.isBlank() || deviceId.isBlank() || clientId.isBlank() || wakeWords.isEmpty()) {
            return null
        }
        return RoleProfile(
            id = id,
            displayName = displayName,
            deviceId = deviceId,
            clientId = clientId,
            wakeWords = wakeWords,
            avatarPath = optString("avatarPath").trim(),
            isBound = if (has("isBound")) optBoolean("isBound") else true,
            bindingCode = optString("bindingCode").trim(),
            idleVideoPath = optString("idleVideoPath").trim(),
            greetingVideoPath = optString("greetingVideoPath").trim(),
            listeningVideoPath = optString("listeningVideoPath").trim(),
            speakingVideoPath = optString("speakingVideoPath").trim(),
        )
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    companion object {
        const val DEFAULT_ROLE_ID = "xiaozhi"
        const val ROLE_CONFIG_FILE_NAME = "roles.json"
    }
}
