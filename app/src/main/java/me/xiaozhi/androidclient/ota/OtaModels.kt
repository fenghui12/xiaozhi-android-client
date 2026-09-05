package me.xiaozhi.androidclient.ota

data class OtaVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val releaseNotes: String = "",
    val forceUpdate: Boolean = false,
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class HasUpdate(val info: OtaVersionInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

sealed interface DownloadProgressState {
    data object Idle : DownloadProgressState
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgressState
    data object Verifying : DownloadProgressState
    data object ReadyToInstall : DownloadProgressState
    data class Failed(val error: String) : DownloadProgressState
}
