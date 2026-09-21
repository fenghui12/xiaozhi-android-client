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
        val target = targetFor(role, slot)
        replaceAtomically(target) { temp ->
            context.contentResolver.openInputStream(source)?.use { input ->
                copyWithLimit(input, temp)
            } ?: error("无法读取视频文件")
        }
        target.absolutePath
    }

    fun importVideoFile(role: RoleProfile, slot: DigitalHumanSlot, source: File): Result<String> = runCatching {
        val target = targetFor(role, slot)
        replaceAtomically(target) { temp ->
            source.inputStream().use { input -> copyWithLimit(input, temp) }
        }
        target.absolutePath
    }

    private fun targetFor(role: RoleProfile, slot: DigitalHumanSlot): File {
        val targetDir = File(context.filesDir, "roles/${safe(role.id)}/digital-human").apply { mkdirs() }
        return File(targetDir, "${slot.wireName}.mp4")
    }

    /**
     * 把 [write] 写进临时文件，**校验通过之后**才原子替换 [target]。
     *
     * 为什么不能直接往 target 写：`target.outputStream()` 会立刻把正式文件**截断**，
     * 之后只要复制中途出问题（选到损坏视频、磁盘满、读取异常）或者 `validate()` 不通过，
     * 返回的虽然是"失败"——但用户**原来那份能用的素材已经被毁了**，而且配置里还指向它。
     *
     * 换成"临时文件 → 校验 → 原子改名"之后，任何失败路径都只影响临时文件，
     * 正式素材在成功之前一个字节都不会被动。临时文件放在同一目录，
     * 保证 `ATOMIC_MOVE` 生效（跨文件系统的 move 不是原子的）。
     */
    private fun replaceAtomically(target: File, write: (File) -> Unit) {
        val temp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        try {
            write(temp)
            validate(temp).getOrThrow()
            try {
                java.nio.file.Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // 极少数文件系统不支持原子改名，退化成"先删再改"。
                // 这一步仍可能留下空档，但同一目录下几乎不会走到这里。
                target.delete()
                if (!temp.renameTo(target)) error("替换素材文件失败")
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /**
     * 带字节上限的复制。
     *
     * `validate()` 里也有 100 MB 的检查，但那是在**复制完之后**才做的——
     * 选到一个几 GB 的文件会先把磁盘写满才发现。这里边拷边拦。
     */
    private fun copyWithLimit(input: java.io.InputStream, target: File) {
        target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_VIDEO_BYTES) error("视频文件不能超过 100 MB")
                output.write(buffer, 0, count)
            }
        }
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

    /**
     * 删除**单个槽位**的视频文件。
     *
     * 为什么要单独做：原来的删除入口只有一个"清空全部"，一次把四段视频 + 头像 + 立绘
     * 全删掉，而数字人素材是用户花时间一段段配出来的。用户原话——
     * 「好不容易配好了，不小心按到垃圾桶，就直接全删了……总共也就 4 个，逐个删除也不算麻烦」。
     */
    fun deleteSlot(role: RoleProfile, slot: DigitalHumanSlot): Boolean {
        val target = targetFor(role, slot)
        return target.exists() && target.delete()
    }

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
