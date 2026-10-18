package app.subsonar.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.subsonar.R
import app.subsonar.data.Subscription
import app.subsonar.ui.MainActivity
import app.subsonar.ui.formatDateMedium
import app.subsonar.ui.formatMoney
import java.time.LocalDate
import java.util.Locale

object ReminderNotifier {
    const val CHANNEL_ID = "renewal_reminders"

    /** (Re)creates the notification channels; calling it again after a language change updates their names. */
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_reminders_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_reminders_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(PriceAlertNotifier.CHANNEL_ID, context.getString(R.string.channel_price_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_price_desc) },
        )
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean =
        hasPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    @SuppressLint("MissingPermission") // checked by canNotify() above + SecurityException guard
    fun notify(context: Context, sub: Subscription, renewal: LocalDate, daysUntil: Long, locale: Locale = Locale.getDefault()) {
        if (!canNotify(context)) return
        val title = when (daysUntil) {
            0L -> context.getString(R.string.notif_title_today, sub.name)
            1L -> context.getString(R.string.notif_title_tomorrow, sub.name)
            else -> context.resources.getQuantityString(R.plurals.notif_title_days, daysUntil.toInt(), sub.name, daysUntil.toInt())
        }
        val body = context.getString(
            R.string.notif_body,
            formatMoney(sub.price, sub.currencyCode, locale),
            formatDateMedium(renewal, locale),
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, sub.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(sub.id.toInt(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; nothing to do.
        }
    }
}
