package me.xiaozhi.androidclient.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
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
import java.io.ByteArrayOutputStream
import java.io.File
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

/**
 * 摄像头模组在整机里是倒装的，拍出来的画面需要转 180° 才是正的；
 * 之前写死为 0，导致云端模型看到的是倒像。
 *
 * 注意：不要用 `CaptureRequest.JPEG_ORIENTATION` 来做这件事——它只改 EXIF 标签、
 * 不动像素数据。实测设成 180 之后，文件仍是 1280x720 的原始朝向 + Orientation=3，
 * 云端视觉链路一旦不读 EXIF，模型看到的照样是倒的。
 * 所以这里保持 EXIF 为正常朝向，自己把像素转正后再上传。
 */
private const val CAMERA_JPEG_ORIENTATION = 0
private const val CAMERA_PIXEL_ROTATION_DEGREES = 180
private const val CAMERA_JPEG_QUALITY = 92
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

    /**
     * 把拍摄到的 JPEG 像素真正旋转到位（而不是只写 EXIF 方向标签），
     * 这样无论云端视觉链路读不读 EXIF，模型看到的都是正的。
     */
    private fun rotatePixels(bytes: ByteArray): ByteArray {
        if (CAMERA_PIXEL_ROTATION_DEGREES == 0) return bytes
        return runCatching {
            val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val matrix = Matrix().apply { postRotate(CAMERA_PIXEL_ROTATION_DEGREES.toFloat()) }
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            val output = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, CAMERA_JPEG_QUALITY, output)
            if (rotated !== source) rotated.recycle()
            source.recycle()
            output.toByteArray()
        }.getOrDefault(bytes)
    }

    /**
     * 把最近一次拍摄的原始 JPEG 写到应用外部私有目录，供开发期核对画面方向。
     * 只保留最近一帧，不累积。
     */
    private fun dumpCaptureForDebug(bytes: ByteArray) {
        runCatching {
            val dir = context.getExternalFilesDir("captures") ?: return
            if (!dir.exists()) dir.mkdirs()
            File(dir, "last_capture.jpg").writeBytes(bytes)
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
                    val normalized = rotatePixels(bytes)
                    dumpCaptureForDebug(normalized)
                    complete(Result.success(normalized))
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
                                            set(CaptureRequest.JPEG_ORIENTATION, CAMERA_JPEG_ORIENTATION)
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
