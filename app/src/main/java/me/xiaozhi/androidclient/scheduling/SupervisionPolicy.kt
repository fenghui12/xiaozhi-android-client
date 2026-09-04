package me.xiaozhi.androidclient.scheduling

object SupervisionPolicy {
    fun canScheduleVerification(phase: SupervisionPhase?): Boolean =
        phase == SupervisionPhase.WAITING_FOR_ACK || phase == SupervisionPhase.VERIFYING

    fun phaseWhenDue(phase: SupervisionPhase?): SupervisionPhase? = when (phase) {
        SupervisionPhase.COUNTDOWN -> SupervisionPhase.WAITING_FOR_ACK
        SupervisionPhase.VERIFICATION_SCHEDULED -> SupervisionPhase.VERIFYING
        else -> null
    }

    fun migrateLegacyPhase(checkCount: Int, hasDueTime: Boolean): SupervisionPhase = when {
        checkCount == 0 && hasDueTime -> SupervisionPhase.COUNTDOWN
        checkCount == 0 -> SupervisionPhase.WAITING_FOR_ACK
        hasDueTime -> SupervisionPhase.VERIFICATION_SCHEDULED
        else -> SupervisionPhase.VERIFYING
    }

    fun nextRetrySeconds(checkCount: Int): Int =
        (checkCount.coerceAtLeast(1) * 30 + 60).coerceAtMost(1_800)
}
