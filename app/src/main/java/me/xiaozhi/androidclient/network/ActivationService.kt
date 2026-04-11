package me.xiaozhi.androidclient.network

import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.xiaozhi.androidclient.model.ActivationInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val ACTIVATION_POLL_INTERVAL_MS = 5_000L
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

data class ActivationRequest(
    val otaUrl: String,
    val deviceId: String,
    val clientId: String,
    val serialNumber: String,
    val hmacKey: String,
    val activationInfo: ActivationInfo,
)

class ActivationService(private val httpClient: OkHttpClient) {
    suspend fun activate(
        request: ActivationRequest,
        onProgress: (String) -> Unit = {},
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val challenge = request.activationInfo.challenge
                ?: throw IOException("Activation challenge is missing")
            val activateUrl = buildActivateUrl(request.otaUrl)
            val maxAttempts = max(
                1,
                ((max(request.activationInfo.timeoutMs ?: 0, 300_000) / ACTIVATION_POLL_INTERVAL_MS.toInt())),
            )

            repeat(maxAttempts) { attempt ->
                currentCoroutineContext().ensureActive()
                onProgress("Activation attempt ${attempt + 1}/$maxAttempts")
                val response = httpClient.newCall(buildHttpRequest(request, activateUrl, challenge)).execute()
                val (statusCode, statusMessage, body) = response.use { httpResponse ->
                    Triple(
                        httpResponse.code,
                        httpResponse.message,
                        httpResponse.body?.string().orEmpty(),
                    )
                }

                when (statusCode) {
                    200 -> return@runCatching true
                    202 -> {
                        onProgress("Waiting for activation confirmation")
                        delay(ACTIVATION_POLL_INTERVAL_MS)
                    }

                    else -> {
                        throw IOException(
                            "Activation failed: $statusCode $statusMessage $body",
                        )
                    }
                }
            }

            false
        }
    }

    private fun buildHttpRequest(
        request: ActivationRequest,
        activateUrl: String,
        challenge: String,
    ): Request {
        val body = JSONObject()
            .put("algorithm", "hmac-sha256")
            .put("serial_number", request.serialNumber)
            .put("challenge", challenge)
            .put("hmac", createHmac(request.hmacKey, challenge))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        return Request.Builder()
            .url(activateUrl)
            .post(body)
            .header("Activation-Version", "2")
            .header("Device-Id", request.deviceId)
            .header("Client-Id", request.clientId)
            .header("Serial-Number", request.serialNumber)
            .header("Content-Type", "application/json")
            .build()
    }

    private fun buildActivateUrl(otaUrl: String): String {
        return if (otaUrl.endsWith("/")) "${otaUrl}activate" else "$otaUrl/activate"
    }

    private fun createHmac(secret: String, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal(challenge.toByteArray())
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it.toInt() and 0xFF)) }
        }
    }
}
