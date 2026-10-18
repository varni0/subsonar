package app.subsonar.reminders

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Reminders use a once-a-day WorkManager job (battery friendly, survives reboots, no exact-alarm
 * permission needed). The job is idempotent: each renewal is notified at most once.
 */
object ReminderScheduler {
    private const val DAILY_WORK = "renewal_reminders_daily"
    private const val NOW_WORK = "renewal_reminders_now"
    private val REMINDER_TIME: LocalTime = LocalTime.of(9, 0)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilNextRun(LocalDateTime.now()).toMinutes(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(DAILY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Re-check immediately, e.g. after the user adds a subscription that renews tomorrow. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>().build()
        )
    }

    internal fun delayUntilNextRun(now: LocalDateTime): Duration {
        var next = now.toLocalDate().atTime(REMINDER_TIME)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
