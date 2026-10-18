package app.subsonar.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.subsonar.SubsonarApp
import app.subsonar.domain.RenewalCalculator
import app.subsonar.ui.AppLanguages
import java.time.LocalDate

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SubsonarApp).container
        val settings = container.settings.current()
        if (!settings.remindersEnabled || !ReminderNotifier.canNotify(applicationContext)) return Result.success()

        val today = LocalDate.now()
        val due = ReminderPolicy.dueReminders(container.subscriptions.getAll(), settings.reminderLeadDays, today)
        // Notification text in the app language (on Android < 13 the application context does not know it).
        val ctx = AppLanguages.localizedContext(applicationContext, settings.languageTag)
        for ((sub, renewal) in due) {
            ReminderNotifier.notify(ctx, sub, renewal, RenewalCalculator.daysUntil(renewal, today), AppLanguages.localeOf(ctx))
            container.subscriptions.markReminded(sub.id, renewal)
        }
        return Result.success()
    }
}
