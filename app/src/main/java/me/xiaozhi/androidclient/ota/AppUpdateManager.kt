package me.xiaozhi.androidclient.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class AppUpdateManager(
    private val context: Context,
    private val baseHttpClient: OkHttpClient,
    private val updateIndexUrl: String = DEFAULT_UPDATE_INDEX_URL,
) {
    /** 下载 54MB 安装包用：超时给得宽，弱网下也不至于半途而废。 */
    private val httpClient = baseHttpClient.newBuilder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 只用来拉 version.json 的客户端：**超时必须短**。
     *
     * version.json 只有几百字节，正常一两秒就该回来。之前它和下载共用上面那个
     * 「读超时 300 秒」的客户端，于是第一个候选源在部分网络下挂住时，用户要盯着
     * "正在检查更新" 干等整整五分钟——2026-09-16 用户反馈的"卡在那里"就是这个。
     * 查询配置不需要那么宽的容忍度：一个源 8 秒没动静就换下一个，
     * 三个源加起来最多也就二十几秒，而且正常情况下第一个（镜像）就成功了。
     */
    private val configHttpClient = baseHttpClient.newBuilder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _downloadState = MutableStateFlow<DownloadProgressState>(DownloadProgressState.Idle)
    val downloadState: StateFlow<DownloadProgressState> = _downloadState.asStateFlow()

    @Volatile
    private var downloadedApkFile: File? = null

    suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            // 顺序很重要：**镜像优先，GitHub 原链放最后**。
            // raw.githubusercontent.com 在部分网络下不是快速失败而是直接挂住，
            // 把它排在第一个，用户就要先白等一个超时才轮到能用的镜像。
            // 两个镜像在国内都能直连，正常情况下第一个就返回了。
            val candidateIndexUrls = listOf(
                "https://ghfast.top/$updateIndexUrl",
                "https://gh-proxy.com/$updateIndexUrl",
                updateIndexUrl
            )

            var lastErrorMsg = "检测更新失败"
            for (url in candidateIndexUrls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Cache-Control", "no-cache")
                        .build()

                    configHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            val remoteVersionCode = if (json.has("versionCode")) json.optInt("versionCode", 0) else 0
                            val remoteVersionName = if (json.has("versionName")) (json.optString("versionName", "") ?: "") else ""
                            val downloadUrl = if (json.has("downloadUrl")) (json.optString("downloadUrl", "") ?: "") else ""
                            val sha256 = (if (json.has("sha256")) (json.optString("sha256", "") ?: "") else "").trim().lowercase()
                            val releaseNotes = if (json.has("releaseNotes")) (json.optString("releaseNotes", "") ?: "") else ""
                            val forceUpdate = json.optBoolean("forceUpdate", false)

                            if (remoteVersionCode > currentVersionCode && downloadUrl.isNotBlank()) {
                                val info = OtaVersionInfo(
                                    versionCode = remoteVersionCode,
                                    versionName = remoteVersionName,
                                    downloadUrl = downloadUrl,
                                    sha256 = sha256,
                                    releaseNotes = releaseNotes,
                                    forceUpdate = forceUpdate,
                                )
                                return@withContext UpdateCheckResult.HasUpdate(info)
                            } else {
                                return@withContext UpdateCheckResult.UpToDate
                            }
                        }
                    } else {
                        lastErrorMsg = "HTTP ${response.code}"
                    }
                    }
                } catch (e: Exception) {
                    lastErrorMsg = e.message ?: "网络连接异常"
                }
            }
            UpdateCheckResult.Error(lastErrorMsg)
        }.getOrElse { e ->
            UpdateCheckResult.Error(e.message ?: "网络连接异常")
        }
    }

    suspend fun downloadApk(
        info: OtaVersionInfo,
        onSuccess: (File) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            _downloadState.value = DownloadProgressState.Downloading(0, 0, 0)

            // 构建候选下载 URL 列表（支持 GitHub 原链及多个国内 CDN 镜像源自动故障转移）
            val candidateUrls = mutableListOf<String>()
            if (info.downloadUrl.contains("github.com")) {
                candidateUrls.add("https://ghfast.top/${info.downloadUrl.replace("https://ghproxy.net/", "").replace("https://mirror.ghproxy.com/", "")}")
                candidateUrls.add("https://gh-proxy.com/${info.downloadUrl.replace("https://ghproxy.net/", "").replace("https://mirror.ghproxy.com/", "")}")
            }
            candidateUrls.add(info.downloadUrl)

            var lastError: Throwable? = null
            var finalApkFile: File? = null

            for (url in candidateUrls) {
                try {
                    android.util.Log.d("AppUpdateManager", "Trying to download APK from: $url")
                    finalApkFile = attemptDownload(url, info)
                    break
                } catch (e: Exception) {
                    android.util.Log.w("AppUpdateManager", "Download failed from $url: ${e.message}")
                    lastError = e
                }
            }

            val apkFile = finalApkFile ?: throw (lastError ?: IllegalStateException("所有下载源均失败"))

            _downloadState.value = DownloadProgressState.Verifying
            if (info.sha256.isNotBlank()) {
                val actualSha256 = calculateSha256(apkFile)
                if (!actualSha256.equals(info.sha256, ignoreCase = true)) {
                    apkFile.delete()
                    throw IllegalStateException("安装包 SHA-256 校验失败 (预期: ${info.sha256}, 实际: $actualSha256)")
                }
            }

            downloadedApkFile = apkFile
            _downloadState.value = DownloadProgressState.ReadyToInstall
            onSuccess(apkFile)
            apkFile
        }.onFailure { err ->
            _downloadState.value = DownloadProgressState.Failed(err.message ?: "下载失败")
        }
    }

    private fun attemptDownload(url: String, info: OtaVersionInfo): File {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) XiaozhiClient/1.1.0")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }

            val body = response.body ?: throw IllegalStateException("下载内容为空")
            val totalBytes = body.contentLength()
            val cacheDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(cacheDir, "xiaozhi-update-${info.versionCode}.apk")
            if (apkFile.exists()) apkFile.delete()

            val input: InputStream = body.byteStream()
            val output = FileOutputStream(apkFile)

            try {
                input.use { inStream ->
                    output.use { outStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesDownloaded = 0L
                var lastPercent = 0
                while (true) {
                    val count = inStream.read(buffer)
                    if (count < 0) break
                    outStream.write(buffer, 0, count)
                    bytesDownloaded += count

                    if (totalBytes > 0) {
                        val percent = ((bytesDownloaded * 100) / totalBytes).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _downloadState.value = DownloadProgressState.Downloading(percent, bytesDownloaded, totalBytes)
                        }
                    }
                }
                    }
                }
            } catch (error: Throwable) {
                apkFile.delete()
                throw error
            }
            apkFile
        }
    }

    fun installApk(apkFile: File): Boolean {
        if (!apkFile.exists()) return false

        // 1. 优先尝试系统级静默安装 (适用于有 root 权限或 RK3568 工控板预置系统命令)
        if (trySilentInstall(apkFile)) {
            return true
        }

        // 2. 静默安装不适用时，降级拉起标准系统安装器
        return tryPackageInstaller(apkFile)
    }

    private fun trySilentInstall(apkFile: File): Boolean {
        // RK3568 工控主板 su 语法支持: su 0 pm install -r -d <path>
        val commands = listOf(
            arrayOf("su", "0", "pm", "install", "-r", "-d", apkFile.absolutePath),
            arrayOf("su", "-c", "pm install -r -d ${apkFile.absolutePath}"),
            arrayOf("pm", "install", "-r", "-d", apkFile.absolutePath),
        )
        for (cmd in commands) {
            val success = runCatching {
                val process = Runtime.getRuntime().exec(cmd)
                val exitCode = process.waitFor()
                exitCode == 0
            }.getOrDefault(false)

            if (success) {
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("am", "start", "-n", "${context.packageName}/.MainActivity"))
                }
                return true
            }
        }
        return false
    }

    private fun tryPackageInstaller(apkFile: File): Boolean {
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile,
                )
                setDataAndType(uri, "application/vnd.android.package-archive")
            }
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            android.util.Log.e("AppUpdateManager", "Failed to launch package installer", e)
            false
        }
    }

    fun resetState() {
        _downloadState.value = DownloadProgressState.Idle
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inStream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = inStream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_UPDATE_INDEX_URL =
            "https://raw.githubusercontent.com/fenghui12/xiaozhi-android-client/dev-main/version.json"
    }
}
