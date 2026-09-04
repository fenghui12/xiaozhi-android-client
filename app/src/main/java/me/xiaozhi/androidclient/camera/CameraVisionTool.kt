package me.xiaozhi.androidclient.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val CAMERA_CAPTURE_TIMEOUT_MS = 12_000L
data class VisionEndpoint(
    val url: String,
    val token: String,
)

class CameraVisionTool(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val deviceId: () -> String,
    private val clientId: () -> String,
) {
    @Volatile
    private var visionEndpoint: VisionEndpoint? = null

    fun updateVisionEndpoint(url: String?, token: String?) {
        visionEndpoint = url?.takeIf { it.isNotBlank() }?.let {
            VisionEndpoint(url = it, token = token.orEmpty())
        }
    }

    suspend fun takePhotoAndExplain(question: String): String {
        require(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Android camera permission is not granted"
        }
        val endpoint = requireNotNull(visionEndpoint) {
            "Vision upload endpoint has not been provided by the server"
        }
        val jpeg = captureJpeg()
        return uploadForExplanation(endpoint, question, jpeg)
    }

    private suspend fun uploadForExplanation(
        endpoint: VisionEndpoint,
        question: String,
        jpeg: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("question", question)
            .addFormDataPart(
                "file",
                "camera.jpg",
                jpeg.toRequestBody("image/jpeg".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(endpoint.url)
            .header("Device-Id", deviceId())
            .header("Client-Id", clientId())
            .apply {
                if (endpoint.token.isNotBlank()) {
                    header("Authorization", "Bearer ${endpoint.token}")
                }
            }
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Vision upload failed: HTTP ${response.code}")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Vision service returned an empty response")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureJpeg(): ByteArray = suspendCancellableCoroutine { continuation ->
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val cameraId = try {
            selectCameraId(cameraManager)
        } catch (error: Exception) {
            continuation.resumeWith(Result.failure(error))
            return@suspendCancellableCoroutine
        }

        val thread = HandlerThread("xiaozhi-camera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
        val finished = AtomicBoolean(false)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        fun closeResources() {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader.close() }
            thread.quitSafely()
        }

        fun complete(result: Result<ByteArray>) {
            if (finished.compareAndSet(false, true)) {
                closeResources()
                continuation.resumeWith(result)
            }
        }

        reader.setOnImageAvailableListener({ imageReader ->
            try {
                imageReader.acquireLatestImage().use { image ->
                    val buffer = image.planes.firstOrNull()?.buffer
                        ?: throw IOException("Camera returned an empty JPEG frame")
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    complete(Result.success(bytes))
                }
            } catch (error: Exception) {
                complete(Result.failure(error))
            }
        }, handler)

        val timeout = Runnable {
            complete(Result.failure(IOException("Camera capture timed out after ${CAMERA_CAPTURE_TIMEOUT_MS}ms")))
        }
        handler.postDelayed(timeout, CAMERA_CAPTURE_TIMEOUT_MS)

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeout)
            if (finished.compareAndSet(false, true)) {
                closeResources()
            }
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(openedDevice: CameraDevice) {
                    if (finished.get()) {
                        openedDevice.close()
                        return
                    }
                    device = openedDevice
                    openedDevice.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(captureSession: CameraCaptureSession) {
                                if (finished.get()) {
                                    captureSession.close()
                                    return
                                }
                                session = captureSession
                                try {
                                    val request = openedDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                        .apply {
                                            addTarget(reader.surface)
                                            set(CaptureRequest.JPEG_ORIENTATION, 0)
                                        }
                                        .build()
                                    captureSession.capture(
                                        request,
                                        object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureFailed(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                failure: CaptureFailure,
                                            ) {
                                                complete(Result.failure(IOException("Camera capture failed: ${failure.reason}")))
                                            }

                                            override fun onCaptureCompleted(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: TotalCaptureResult,
                                            ) = Unit
                                        },
                                        handler,
                                    )
                                } catch (error: Exception) {
                                    complete(Result.failure(error))
                                }
                            }

                            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                                complete(Result.failure(IOException("Camera capture session configuration failed")))
                            }
                        },
                        handler,
                    )
                }

                override fun onDisconnected(disconnectedDevice: CameraDevice) {
                    complete(Result.failure(IOException("Camera was disconnected")))
                }

                override fun onError(errorDevice: CameraDevice, error: Int) {
                    complete(Result.failure(IOException("Camera failed to open: $error")))
                }
            }, handler)
        } catch (error: Exception) {
            complete(Result.failure(error))
        }
    }

    private fun selectCameraId(cameraManager: CameraManager): String {
        return cameraManager.cameraIdList
            .firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
            }
            ?: throw IOException("No external USB camera is available")
    }
}
