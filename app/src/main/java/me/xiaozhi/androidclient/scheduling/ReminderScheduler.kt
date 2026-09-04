package me.xiaozhi.androidclient.scheduling

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

enum class ReminderKind { TIMER, SUPERVISION }

enum class SupervisionPhase {
    COUNTDOWN,
    WAITING_FOR_ACK,
    VERIFICATION_SCHEDULED,
    VERIFYING,
}

data class ScheduledReminder(
    val id: String,
    val kind: ReminderKind,
    val message: String,
    val dueAtEpochMs: Long?,
    val checkCount: Int = 0,
    val supervisionPhase: SupervisionPhase? = null,
    val deliveryPending: Boolean = false,
)

class ReminderScheduler(
    context: Context,
    private val scope: CoroutineScope,
    private val onDue: (ScheduledReminder) -> Unit,
    private val onSnapshot: (List<ScheduledReminder>) -> Unit = {},
) {
    private val prefs = context.getSharedPreferences("xiaozhi_reminders", Context.MODE_PRIVATE)
    private val reminders = linkedMapOf<String, ScheduledReminder>()
    private var tickerJob: Job? = null

    init {
        load()
    }

    @Synchronized
    fun start() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            synchronized(this@ReminderScheduler) {
                reminders.values.filter {
                    it.kind == ReminderKind.SUPERVISION && it.deliveryPending
                }
            }.forEach(onDue)
            while (isActive) {
                tick()
                onSnapshot(snapshot())
                delay(1_000L)
            }
        }
    }

    @Synchronized
    fun setTimer(seconds: Int, time: String?, message: String): ScheduledReminder? {
        if (seconds < 0 || seconds > 86_400) return null
        val dueAt = if (seconds > 0) {
            System.currentTimeMillis() + seconds * 1_000L
        } else {
            parseClockTime(time) ?: return null
        }
        val reminder = ScheduledReminder(
            id = "timer-${UUID.randomUUID()}",
            kind = ReminderKind.TIMER,
            message = message.ifBlank { "时间到了" },
            dueAtEpochMs = dueAt,
        )
        reminders[reminder.id] = reminder
        persist()
        onSnapshot(snapshot())
        return reminder
    }

    @Synchronized
    fun startSupervision(seconds: Int, message: String): ScheduledReminder? {
        if (reminders.values.any { it.kind == ReminderKind.SUPERVISION }) return null
        val reminder = ScheduledReminder(
            id = "supervision-${UUID.randomUUID()}",
            kind = ReminderKind.SUPERVISION,
            message = message,
            dueAtEpochMs = System.currentTimeMillis() + seconds * 1_000L,
            supervisionPhase = SupervisionPhase.COUNTDOWN,
        )
        reminders[reminder.id] = reminder
        persist()
        onSnapshot(snapshot())
        return reminder
    }

    @Synchronized
    fun scheduleSupervisionVerification(reminderId: String, seconds: Int): Boolean {
        val current = reminders[reminderId]?.takeIf { it.kind == ReminderKind.SUPERVISION } ?: return false
        if (!SupervisionPolicy.canScheduleVerification(current.supervisionPhase)) return false
        reminders[current.id] = current.copy(
            dueAtEpochMs = System.currentTimeMillis() + seconds * 1_000L,
            checkCount = current.checkCount + 1,
            supervisionPhase = SupervisionPhase.VERIFICATION_SCHEDULED,
        )
        persist()
        onSnapshot(snapshot())
        return true
    }

    @Synchronized
    fun completeSupervision(reminderId: String): Boolean = removeSupervision(reminderId)

    @Synchronized
    fun completeTimer(reminderId: String): Boolean {
        val current = reminders[reminderId]?.takeIf { it.kind == ReminderKind.TIMER } ?: return false
        reminders.remove(current.id)
        persist()
        onSnapshot(snapshot())
        return true
    }

    @Synchronized
    fun cancelSupervision(): Boolean = removeSupervision()

    @Synchronized
    fun activeSupervision(): ScheduledReminder? = reminders.values.firstOrNull { it.kind == ReminderKind.SUPERVISION }

    @Synchronized
    fun activeTimer(): ScheduledReminder? = reminders.values
        .firstOrNull { it.kind == ReminderKind.TIMER && it.deliveryPending }
        ?: reminders.values.firstOrNull { it.kind == ReminderKind.TIMER }

    @Synchronized
    fun snapshot(): List<ScheduledReminder> = reminders.values.toList()

    private fun removeSupervision(reminderId: String? = null): Boolean {
        val current = reminders.values.firstOrNull { it.kind == ReminderKind.SUPERVISION } ?: return false
        if (reminderId != null && current.id != reminderId) return false
        reminders.remove(current.id)
        persist()
        onSnapshot(snapshot())
        return true
    }

    private fun tick() {
        val due = synchronized(this) {
            val now = System.currentTimeMillis()
            reminders.values.filter { it.dueAtEpochMs != null && now >= it.dueAtEpochMs }
        }
        due.forEach { reminder ->
            val shouldDeliver = synchronized(this) {
                val current = reminders[reminder.id] ?: return@synchronized false
                if (current.dueAtEpochMs == null || System.currentTimeMillis() < current.dueAtEpochMs) {
                    return@synchronized false
                }
                if (current.kind == ReminderKind.TIMER) {
                    reminders[current.id] = current.copy(
                        dueAtEpochMs = null,
                        deliveryPending = true,
                    )
                } else {
                    val nextPhase = SupervisionPolicy.phaseWhenDue(current.supervisionPhase)
                        ?: return@synchronized false
                    reminders[current.id] = current.copy(
                        dueAtEpochMs = null,
                        supervisionPhase = nextPhase,
                        deliveryPending = true,
                    )
                }
                persist()
                true
            }
            if (shouldDeliver) onDue(reminder)
        }
    }

    private fun parseClockTime(value: String?): Long? {
        val text = value?.trim() ?: return null
        val parts = text.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(zone).toInstant().toEpochMilli()
    }

    @Synchronized
    private fun persist() {
        val array = JSONArray()
        reminders.values.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("kind", item.kind.name)
                .put("message", item.message)
                .put("dueAt", item.dueAtEpochMs ?: JSONObject.NULL)
                .put("checkCount", item.checkCount)
                .put("supervisionPhase", item.supervisionPhase?.name ?: JSONObject.NULL)
                .put("deliveryPending", item.deliveryPending))
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private fun load() {
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrNull() ?: return
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val kind = runCatching { ReminderKind.valueOf(item.optString("kind")) }.getOrNull() ?: continue
            val dueAt = if (item.isNull("dueAt")) null else item.optLong("dueAt")
            val storedPhase = item.optString("supervisionPhase")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { SupervisionPhase.valueOf(it) }.getOrNull() }
            val checkCount = item.optInt("checkCount")
            var phase = if (kind == ReminderKind.SUPERVISION) {
                storedPhase ?: SupervisionPolicy.migrateLegacyPhase(checkCount, dueAt != null)
            } else null
            val deliveryPending = item.optBoolean("deliveryPending", false) ||
                (kind == ReminderKind.SUPERVISION &&
                    phase == SupervisionPhase.WAITING_FOR_ACK &&
                    dueAt == null)
            var normalizedDueAt = if (kind == ReminderKind.TIMER && deliveryPending) {
                System.currentTimeMillis()
            } else dueAt
            if (phase == SupervisionPhase.VERIFYING) {
                phase = SupervisionPhase.VERIFICATION_SCHEDULED
                normalizedDueAt = System.currentTimeMillis()
            }
            val reminder = ScheduledReminder(
                id = item.optString("id"),
                kind = kind,
                message = item.optString("message"),
                dueAtEpochMs = normalizedDueAt,
                checkCount = checkCount,
                supervisionPhase = phase,
                deliveryPending = deliveryPending,
            )
            if (reminder.id.isNotBlank() && reminder.message.isNotBlank()) reminders[reminder.id] = reminder
        }
    }
}
