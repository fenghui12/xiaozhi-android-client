package me.xiaozhi.androidclient.network

import android.os.Build
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.xiaozhi.androidclient.model.ActivationInfo
import me.xiaozhi.androidclient.model.OtaConfigResult
import me.xiaozhi.androidclient.model.OtaRequest
import me.xiaozhi.androidclient.model.WebsocketConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class OtaConfigService(private val httpClient: OkHttpClient) {
    suspend fun fetchConfig(request: OtaRequest): Result<OtaConfigResult> = withContext(Dispatchers.IO) {
        runCatching {
            val response = httpClient.newCall(buildHttpRequest(request)).execute()
            response.use { httpResponse ->
                val body = httpResponse.body?.string().orEmpty()
                if (!httpResponse.isSuccessful) {
                    throw IOException("OTA request failed: ${httpResponse.code} ${httpResponse.message} $body")
                }
                parseResponse(body)
            }
        }
    }

    private fun buildHttpRequest(request: OtaRequest): Request {
        val activationVersion = if (request.serialNumber.isNullOrBlank()) "1" else "2"
        val payload = JSONObject()
            .put("version", 2)
            .put("language", Locale.getDefault().toLanguageTag())
            .put("minimum_free_heap_size", 0)
            .put("mac_address", request.deviceId)
            .put("uuid", request.clientId)
            .put("chip_model_name", "android")
            .put(
                "application",
                JSONObject()
                    .put("name", "xiaozhi-android-client")
                    .put("version", request.appVersion)
                    .put("compile_time", "")
                    .put("idf_version", "android-${Build.VERSION.SDK_INT}")
                    .put("elf_sha256", ""),
            )
            .put(
                "board",
                JSONObject()
                    .put("type", "android")
                    .put("name", Build.MODEL ?: "android")
                    .put("mac", request.deviceId)
                    .put("manufacturer", Build.MANUFACTURER ?: "unknown")
                    .put("model", Build.MODEL ?: "unknown")
                    .put("sdk", Build.VERSION.SDK_INT),
            )
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val builder = Request.Builder()
            .url(request.otaUrl)
            .post(payload)
            .header("Activation-Version", activationVersion)
            .header("Device-Id", request.deviceId)
            .header("Client-Id", request.clientId)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", Locale.getDefault().toLanguageTag())
            .header("Content-Type", "application/json")
        if (!request.serialNumber.isNullOrBlank()) {
            builder.header("Serial-Number", request.serialNumber)
        }
        return builder.build()
    }

    private fun parseResponse(body: String): OtaConfigResult {
        val root = JSONObject(body)
        val websocket = root.optJSONObject("websocket")?.let { websocketObject ->
            val url = websocketObject.optStringNullable("url")
                ?: throw IOException("OTA response missing websocket.url")
            WebsocketConfig(
                url = url,
                token = websocketObject.optStringNullable("token"),
                version = websocketObject.optIntNullable("version") ?: 1,
            )
        }
        val activation = root.optJSONObject("activation")?.let { activationObject ->
            ActivationInfo(
                message = activationObject.optStringNullable("message"),
                code = activationObject.optStringNullable("code"),
                challenge = activationObject.optStringNullable("challenge"),
                timeoutMs = activationObject.optIntNullable("timeout_ms"),
            )
        }
        return OtaConfigResult(
            websocket = websocket,
            activation = activation,
            rawBody = body,
        )
    }

    private fun JSONObject.optStringNullable(key: String): String? {
        val value = optString(key)
        return value.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optIntNullable(key: String): Int? {
        return if (has(key) && !isNull(key)) optInt(key) else null
    }

    companion object {
        private const val USER_AGENT = "XiaozhiAndroidClient/0.2"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
