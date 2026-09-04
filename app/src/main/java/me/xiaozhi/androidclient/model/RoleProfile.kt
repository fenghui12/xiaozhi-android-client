package me.xiaozhi.androidclient.model

data class RoleProfile(
    val id: String,
    val displayName: String,
    val deviceId: String,
    val clientId: String,
    val wakeWords: List<String>,
    val avatarPath: String = "",
    val isBound: Boolean = true,
    val bindingCode: String = "",
    val idleVideoPath: String = "",
    val greetingVideoPath: String = "",
    val listeningVideoPath: String = "",
    val speakingVideoPath: String = "",
)

enum class DigitalHumanSlot(val wireName: String, val label: String) {
    IDLE("idle", "待机视频"),
    GREETING("greeting", "打招呼视频"),
    LISTENING("listening", "聆听视频"),
    SPEAKING("speaking", "讲话视频"),
}

fun RoleProfile.videoPath(slot: DigitalHumanSlot): String = when (slot) {
    DigitalHumanSlot.IDLE -> idleVideoPath
    DigitalHumanSlot.GREETING -> greetingVideoPath
    DigitalHumanSlot.LISTENING -> listeningVideoPath
    DigitalHumanSlot.SPEAKING -> speakingVideoPath
}

fun RoleProfile.hasCompleteDigitalHuman(): Boolean =
    listOf(idleVideoPath, greetingVideoPath, listeningVideoPath, speakingVideoPath)
        .all(String::isNotBlank)
