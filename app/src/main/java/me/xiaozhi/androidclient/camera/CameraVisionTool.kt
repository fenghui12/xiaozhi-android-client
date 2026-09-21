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
        // 这两个句柄跨线程读写：`onOpened` / `onDisconnected` / `onError` 在 HandlerThread
        // 的回调线程上写，而 `closeResources()` 还会在**取消该协程的线程**上执行
        // （`invokeOnCancellation` 不在回调线程上跑）。
        //
        // 它们是被闭包捕获的**局部变量**，Kotlin 会编译成引用对象，所以加不了 @Volatile；
        // 而普通读写在 Android 内存模型下不保证跨线程可见 —— 那会让
        // `if (device == null) device = disconnectedDevice` 白写，
        // 本该被关掉的相机设备仍被 closeResources() 跳过，正是要修的那个句柄泄漏。
        // 用 AtomicReference 一次解决两件事：可见性，以及"读-判-写"的原子性。
        val device = java.util.concurrent.atomic.AtomicReference<CameraDevice?>(null)
        val session = java.util.concurrent.atomic.AtomicReference<CameraCaptureSession?>(null)

        fun closeResources() {
            runCatching { session.get()?.close() }
            runCatching { device.get()?.close() }
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
                        runCatching { openedDevice.close() }
                        return
                    }
                    device.set(openedDevice)
                    // 这里整个包 try：`createCaptureSession` 会抛 CameraAccessException /
                    // IllegalStateException（例如 USB 摄像头在打开后、建会话前掉线，
                    // 或 HAL 拒绝会话配置）。此刻代码已经跑在 HandlerThread 的回调里，
                    // 异常逃出去**没有任何人接**，会被线程的默认异常处理器抓住并
                    // **直接结束整个应用进程**。注意外层那个 try 只包住了
                    // `openCamera()` 调用本身，捕不到这个稍后才执行的回调。
                    try {
                        openedDevice.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(captureSession: CameraCaptureSession) {
                                    if (finished.get()) {
                                        runCatching { captureSession.close() }
                                        return
                                    }
                                    session.set(captureSession)
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
                    } catch (error: Exception) {
                        complete(Result.failure(error))
                    }
                }

                override fun onDisconnected(disconnectedDevice: CameraDevice) {
                    // 走到这里说明设备掉线或出错。若 `onOpened` 从没执行过，
                    // closeResources() 里的 device 还是 null，**回调传进来的这个设备
                    // 就没人关了**，会一直占着相机句柄。先把它记下来再收尾。
                    device.compareAndSet(null, disconnectedDevice)
                    complete(Result.failure(IOException("Camera was disconnected")))
                }

                override fun onError(errorDevice: CameraDevice, error: Int) {
                    device.compareAndSet(null, errorDevice)
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
