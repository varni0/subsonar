package app.subsonar.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.subsonar.R
import app.subsonar.data.AppSettings
import app.subsonar.domain.PriceHistoryCalculator
import app.subsonar.ui.AppLanguages
import app.subsonar.ui.MainActivity
import app.subsonar.ui.formatMoney
import app.subsonar.ui.formatPercent
import app.subsonar.ui.formatSignedMoney
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A price increase found while importing bank statements. */
data class PriceAlert(
    val subscriptionId: Long,
    val name: String,
    val currencyCode: String,
    val oldPrice: BigDecimal,
    val newPrice: BigDecimal,
    val effectiveFrom: LocalDate,
    val extraYearly: BigDecimal,
)

/** Pure decision logic: which detected changes deserve a notification (unit tested). */
object PriceAlertPolicy {
    /** Older increases are history, not news. */
    const val MAX_AGE_DAYS = 60L

    /** Alerts are sent only for increases, and only when notifications are wanted at all (reminders on + price alerts on). */
    fun shouldNotify(settings: AppSettings, oldPrice: BigDecimal, newPrice: BigDecimal, effectiveFrom: LocalDate, today: LocalDate): Boolean =
        settings.remindersEnabled && settings.priceAlertsEnabled &&
            newPrice > oldPrice &&
            ChronoUnit.DAYS.between(effectiveFrom, today) in 0..MAX_AGE_DAYS
}

object PriceAlertNotifier {
    const val CHANNEL_ID = "price_changes"
    private const val ID_BASE = 2_000_000

    /** Posts one notification per alert (the number is capped so an import cannot flood the shade). */
    @SuppressLint("MissingPermission") // checked by ReminderNotifier.canNotify + SecurityException guard
    fun notify(context: Context, alerts: List<PriceAlert>, locale: Locale = Locale.getDefault()) {
        if (alerts.isEmpty() || !ReminderNotifier.canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        for (a in alerts.take(MAX_NOTIFICATIONS)) {
            val percent = PriceHistoryCalculator.percentChange(a.oldPrice, a.newPrice)
            val body = context.getString(
                R.string.notif_price_body,
                formatMoney(a.oldPrice, a.currencyCode, locale),
                formatMoney(a.newPrice, a.currencyCode, locale),
                percent?.let { formatPercent(it, locale, signed = true) } ?: formatSignedMoney(a.newPrice - a.oldPrice, a.currencyCode, locale),
                formatMoney(a.extraYearly, a.currencyCode, locale),
            )
            val pending = PendingIntent.getActivity(context, ID_BASE + a.subscriptionId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_price_title, a.name))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(ID_BASE + a.subscriptionId.toInt(), notification)
            } catch (_: SecurityException) {
                // Permission revoked between the check and the call; nothing to do.
            }
        }
    }

    private const val MAX_NOTIFICATIONS = 5
}
