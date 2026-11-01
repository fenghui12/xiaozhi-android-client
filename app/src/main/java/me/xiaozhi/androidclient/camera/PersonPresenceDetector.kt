package me.xiaozhi.androidclient.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.FaceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 主动招呼前的确认要快：超过这个时间就当相机不可用，不让人站着等。 */
private const val PRESENCE_CAPTURE_TIMEOUT_MS = 2_500L

/** 1280×720 缩到 640×360 再检测，够用且快。 */
private const val PRESENCE_SAMPLE_SIZE = 2
private const val MAX_FACES = 3

/** 低于这个可信度的结果丢掉（系统 API 自己的阈值是 0.4）。 */
private const val MIN_FACE_CONFIDENCE = 0.45f

/** 640 宽画面上两眼间距小于这个像素的脸太远或是误检。 */
private const val MIN_EYES_DISTANCE_PX = 10f

data class PresenceCheck(
    val personFound: Boolean,
    val cameraAvailable: Boolean,
    val detail: String,
)

/**
 * 本地、离线的「画面里有没有人」判断：用系统自带的 [FaceDetector]，零依赖、零上传。
 *
 * 局限：它找的是正脸。人背对或侧身时看不到，所以 [me.xiaozhi.androidclient.integration.PresenceGate]
 * 会在一次靠近事件里隔几秒多看几次。效果不够再换 ML Kit / RKNN 人形检测，接口不变。
 */
class PersonPresenceDetector(
    private val cameraVisionTool: CameraVisionTool,
) {
    suspend fun check(): PresenceCheck {
        val jpeg = runCatching {
            cameraVisionTool.capturePresenceFrame(PRESENCE_CAPTURE_TIMEOUT_MS)
        }.getOrElse { error ->
            return PresenceCheck(false, false, "拍照失败：${error.message.orEmpty()}")
        } ?: return PresenceCheck(false, false, "摄像头正被其他功能占用")
        return withContext(Dispatchers.Default) { detectFaces(jpeg) }
    }

    private fun detectFaces(jpeg: ByteArray): PresenceCheck {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = PRESENCE_SAMPLE_SIZE
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: return PresenceCheck(false, false, "照片解码失败")
        // FaceDetector 要求 RGB_565 且宽度为偶数
        val bitmap = if (decoded.width % 2 == 0) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width - 1, decoded.height).also { decoded.recycle() }
        }
        return try {
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val count = FaceDetector(bitmap.width, bitmap.height, MAX_FACES).findFaces(bitmap, faces)
            val accepted = faces.take(count).filterNotNull().filter {
                it.confidence() >= MIN_FACE_CONFIDENCE && it.eyesDistance() >= MIN_EYES_DISTANCE_PX
            }
            val summary = faces.take(count).filterNotNull().joinToString { face ->
                "conf=%.2f eyes=%.0fpx".format(face.confidence(), face.eyesDistance())
            }
            PresenceCheck(
                personFound = accepted.isNotEmpty(),
                cameraAvailable = true,
                detail = "${bitmap.width}×${bitmap.height} 检出 $count 张脸，有效 ${accepted.size}" +
                    if (summary.isNotEmpty()) "（$summary）" else "",
            )
        } finally {
            bitmap.recycle()
        }
    }
}
