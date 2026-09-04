package me.xiaozhi.androidclient.model

import java.util.Locale

internal object RoleWakeWordMatcher {
    fun findOwner(profiles: List<RoleProfile>, detectedPhrase: String): RoleProfile? {
        val normalizedPhrase = normalize(detectedPhrase)
        if (normalizedPhrase.isBlank()) return null
        return profiles.firstOrNull { profile ->
            profile.wakeWords.any { wakeWord -> normalize(wakeWord) == normalizedPhrase }
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s，,。！？!?]"), "")
}
