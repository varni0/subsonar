package app.subsonar

import android.app.Application
import app.subsonar.reminders.ReminderNotifier
import app.subsonar.reminders.ReminderScheduler

open class SubsonarApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderNotifier.createChannel(this)
        scheduleBackgroundWork()
    }

    /** Overridden in screenshot tests, where WorkManager is not initialised. */
    protected open fun scheduleBackgroundWork() = ReminderScheduler.schedule(this)
}
