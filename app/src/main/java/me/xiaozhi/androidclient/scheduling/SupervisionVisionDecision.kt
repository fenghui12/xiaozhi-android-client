package me.xiaozhi.androidclient.scheduling

enum class SupervisionVisionStatus {
    COMPLETED,
    NOT_COMPLETED,
    UNCERTAIN,
}

data class SupervisionVisionDecision(
    val status: SupervisionVisionStatus,
    val rawResponse: String,
)

object SupervisionVisionDecisionParser {
    private val explicitStatus = Regex(
        "(?:\\\"?status\\\"?\\s*[:：]\\s*\\\"?)?(NOT_COMPLETED|COMPLETED|UNCERTAIN)",
        RegexOption.IGNORE_CASE,
    )

    fun parse(response: String): SupervisionVisionDecision {
        val status = explicitStatus.find(response)?.groupValues?.getOrNull(1)
            ?.uppercase()
            ?.let { runCatching { SupervisionVisionStatus.valueOf(it) }.getOrNull() }
            ?: SupervisionVisionStatus.UNCERTAIN
        return SupervisionVisionDecision(status = status, rawResponse = response.trim())
    }
}
