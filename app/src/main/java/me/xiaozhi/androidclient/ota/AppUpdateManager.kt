package me.xiaozhi.androidclient.ota

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
    /** 下载 54MB 安装包用。
     *
     * 读超时从 300 秒收紧到 30 秒：读超时是**单次 read 无数据**的上限，健康的连接
     * 不可能 30 秒一个字节都收不到。之前 300 秒意味着连接一旦中途死掉，进度条会
     * 停住不动干等整整五分钟才回退到下一个源（2026-09-17 实测复现过：停在 9%
     * 五分钟）。30 秒足够慢速但活着的连接继续，又能让死连接快速暴露。
     */
    private val httpClient = baseHttpClient.newBuilder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
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
                    val candidate = attemptDownload(url, info)
                    // 校验放进循环里：某个源返回 HTTP 200 但内容是错误页 / 旧包 /
                    // 缓存串了的时候，应该换下一个源重试，而不是直接判整个升级失败。
                    // 旧写法是先 break 出循环再校验，一旦第一个源的内容不对，
                    // 后面健康的源永远不会被尝试。
                    verifyDownloadedApk(candidate, info)
                    finalApkFile = candidate
                    break
                } catch (e: Exception) {
                    android.util.Log.w("AppUpdateManager", "Download failed from $url: ${e.message}")
                    lastError = e
                }
            }

            val apkFile = finalApkFile ?: throw (lastError ?: IllegalStateException("所有下载源均失败"))

            downloadedApkFile = apkFile
            _downloadState.value = DownloadProgressState.ReadyToInstall
            onSuccess(apkFile)
            apkFile
        }.onFailure { err ->
            _downloadState.value = DownloadProgressState.Failed(err.message ?: "下载失败")
        }
    }

    /**
     * 安装包校验。**这是防「镜像投毒」的关键一环。**
     *
     * 背景：`version.json`（含 versionCode / downloadUrl / sha256）本身也是从镜像拉的，
     * 所以镜像可以**同时伪造**版本描述和哈希。而下面 `installApk()` 会先尝试
     * `su 0 pm install`（root 静默安装），且 `pm install` 不检查"是不是在升级同一个应用"——
     * 换句话说，一个被投毒的镜像原本就能让设备装上**任意包名的任意 APK**。
     *
     * 包名和签名证书是写死在应用里的，镜像伪造不了，所以拿它们做最后一道闸门：
     * 三者（哈希、包名、签名）全部通过才允许安装。
     */
    private fun verifyDownloadedApk(apkFile: File, info: OtaVersionInfo) {
        _downloadState.value = DownloadProgressState.Verifying

        // 1) SHA-256：必须存在且匹配。
        //    旧写法在 sha256 为空时会**跳过**校验，等于给"配置被篡改或截断"留了后门。
        if (info.sha256.isBlank()) {
            apkFile.delete()
            throw IllegalStateException("更新配置缺少 sha256，拒绝安装")
        }
        val actualSha256 = calculateSha256(apkFile)
        if (!actualSha256.equals(info.sha256, ignoreCase = true)) {
            apkFile.delete()
            throw IllegalStateException("安装包 SHA-256 校验失败 (预期: ${info.sha256}, 实际: $actualSha256)")
        }

        // 2) 包名 + 签名证书：确认下载到的确实是"我们自己的"安装包。
        val archive = context.packageManager
            .getPackageArchiveInfo(apkFile.absolutePath, archiveInfoFlags)
            ?: run {
                apkFile.delete()
                throw IllegalStateException("安装包无法解析，可能已损坏")
            }
        if (archive.packageName != context.packageName) {
            apkFile.delete()
            throw IllegalStateException(
                "安装包包名不匹配 (预期 ${context.packageName}，实际 ${archive.packageName})，拒绝安装",
            )
        }
        val signer = signerSha256(archive)
        if (signer == null) {
            apkFile.delete()
            throw IllegalStateException("安装包没有签名信息，拒绝安装")
        }
        if (!signer.equals(EXPECTED_SIGNER_SHA256, ignoreCase = true)) {
            apkFile.delete()
            throw IllegalStateException("安装包签名不匹配 (实际 $signer)，拒绝安装")
        }

        // 3) 版本号一致性：配置里报的版本必须和包内声明的版本一致。
        //    不一致说明配置和包对不上（配置写错或被人拼装过）。放过去的话，
        //    设备可能装上一个比配置里更低的版本，然后被反复提示同一个更新。
        //    longVersionCode 是 API 28 才有的，minSdk 26 要走回退分支。
        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        if (apkVersionCode != info.versionCode.toLong()) {
            apkFile.delete()
            throw IllegalStateException(
                "安装包版本与更新配置不一致 (配置 ${info.versionCode}，实际 $apkVersionCode)",
            )
        }
    }

    private val archiveInfoFlags: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun signerSha256(info: PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val first = signatures?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(first.toByteArray())
            .joinToString("") { "%02x".format(it) }
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

        // 闸门：只允许安装**刚刚通过完整校验的那个文件**。
        // installApk 是公开方法，任何人拿到一个路径都能调；而下面第一条路径是
        // root 静默安装，一旦被传进别的 APK，后果是"静默装上任意应用"。
        //
        // `canonicalPath` 声明会抛 IOException（路径里有解析不了的符号链接、
        // 或父目录被 SELinux 拒绝读取），而 installApk 返回 Boolean、
        // 调用方没有任何 try —— 异常逃出去会直接打断升级协程。
        // 按"拒绝安装"处理，与本闸门 fail-closed 的意图一致。
        val sameFile = runCatching {
            apkFile.canonicalPath == downloadedApkFile?.canonicalPath
        }.getOrDefault(false)
        if (!sameFile) {
            android.util.Log.e("AppUpdateManager", "拒绝安装未经校验的安装包: ${apkFile.absolutePath}")
            return false
        }

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

        /**
         * 本应用签名证书的 SHA-256。**这是防镜像投毒的最后一道闸门，改动前务必想清楚。**
         *
         * 对应 `~/.android/debug.keystore`（v1.2.1 起所有对外发布版本都用它签名，
         * 见 `docs` 里的发布记录）。换签名密钥时必须同步改这里，否则所有设备
         * 都会拒绝安装新版本——那是"升级永久失效"，比装不上一个包严重得多。
         */
        const val EXPECTED_SIGNER_SHA256 =
            "cf16874766d4d343c9e67795adf12b5f12cb3e1096122e73cdde42d7b34dc835"
    }
}
