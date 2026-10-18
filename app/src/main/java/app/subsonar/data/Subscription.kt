package app.subsonar.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDate

enum class BillingCycle { WEEKLY, MONTHLY, QUARTERLY, YEARLY }

enum class Category {
    STREAMING, MUSIC, SOFTWARE, CLOUD, GAMING, NEWS, FITNESS,
    UTILITIES, PHONE_INTERNET, INSURANCE, EDUCATION, OTHER;

    companion object {
        fun fromName(name: String?): Category = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/**
 * A recurring payment.
 *
 * [renewalAnchor] is the renewal date the user entered. The *upcoming* renewal is always
 * derived from it with [app.subsonar.domain.RenewalCalculator], so nothing has to be
 * rewritten in the background when a date passes and month-end dates never drift
 * (e.g. Jan 31 -> Feb 28 -> Mar 31).
 */
@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: BigDecimal,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    val cycle: BillingCycle,
    @ColumnInfo(name = "renewal_anchor") val renewalAnchor: LocalDate,
    val category: Category = Category.OTHER,
    val notes: String = "",
    @ColumnInfo(name = "reminders_enabled") val remindersEnabled: Boolean = true,
    /** Renewal date we last sent a reminder for; prevents duplicate notifications. */
    @ColumnInfo(name = "last_reminded_for") val lastRemindedFor: LocalDate? = null,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long = System.currentTimeMillis(),
    /** Merchant key of the bank-import suggestion this was created from; used to avoid suggesting it again. */
    @ColumnInfo(name = "import_key") val importKey: String? = null,
    /** Avatar colour key chosen by the user (see SubscriptionVisuals.COLORS); null = automatic from the category. */
    @ColumnInfo(name = "color_key") val colorKey: String? = null,
    /** Avatar chosen by the user: "icon:<key>" or "emoji:<emoji>" (see SubscriptionVisuals); null = automatic. */
    @ColumnInfo(name = "avatar") val avatar: String? = null,
)
