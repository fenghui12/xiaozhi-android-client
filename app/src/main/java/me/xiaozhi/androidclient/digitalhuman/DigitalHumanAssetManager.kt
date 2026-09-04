package me.xiaozhi.androidclient.digitalhuman

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.Locale
import me.xiaozhi.androidclient.model.DigitalHumanSlot
import me.xiaozhi.androidclient.model.RoleProfile
import me.xiaozhi.androidclient.model.videoPath

data class DigitalHumanVideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
)

class DigitalHumanAssetManager(private val context: Context) {
    fun importVideo(role: RoleProfile, slot: DigitalHumanSlot, source: Uri): Result<String> = runCatching {
        val resolver = context.contentResolver
        val targetDir = File(context.filesDir, "roles/${safe(role.id)}/digital-human").apply { mkdirs() }
        val target = File(targetDir, "${slot.wireName}.mp4")
        resolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取视频文件")
        validate(target).getOrThrow()
        target.absolutePath
    }

    fun importVideoFile(role: RoleProfile, slot: DigitalHumanSlot, source: File): Result<String> = runCatching {
        val targetDir = File(context.filesDir, "roles/${safe(role.id)}/digital-human").apply { mkdirs() }
        val target = File(targetDir, "${slot.wireName}.mp4")
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        validate(target).getOrThrow()
        target.absolutePath
    }

    fun validate(path: String): Result<DigitalHumanVideoInfo> = validate(File(path))

    fun validate(file: File): Result<DigitalHumanVideoInfo> = runCatching {
        require(file.exists() && file.length() > 0) { "视频文件不存在或为空" }
        require(file.length() <= MAX_VIDEO_BYTES) { "视频文件不能超过 100 MB" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("无法读取视频时长")
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: error("无法读取视频宽度")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: error("无法读取视频高度")
            require(duration in MIN_DURATION_MS..MAX_DURATION_MS) { "视频时长需在 0.5 到 15 秒之间" }
            DigitalHumanVideoInfo(duration, width, height)
        } finally {
            retriever.release()
        }
    }

    fun isComplete(role: RoleProfile): Boolean =
        DigitalHumanSlot.entries.all { role.videoPath(it).isNotBlank() && validate(role.videoPath(it)).isSuccess }

    fun deleteRoleAssets(roleId: String) {
        File(context.filesDir, "roles/${safe(roleId)}/digital-human").deleteRecursively()
    }

    private fun safe(value: String): String = value.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")

    companion object {
        private const val MAX_VIDEO_BYTES = 100L * 1024L * 1024L
        private const val MIN_DURATION_MS = 500L
        private const val MAX_DURATION_MS = 15_000L
    }
}
