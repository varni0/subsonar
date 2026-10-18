package app.subsonar.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDate

/** Where a price history entry came from. */
enum class PriceChangeSource { MANUAL, IMPORT }

/**
 * One recorded price change of a subscription ("effective from [effectiveDate] the price went from [oldPrice] to [newPrice]").
 *
 * The [currencyCode] and [cycle] the prices were quoted in are stored too, so the history stays interpretable if the user later
 * switches the subscription to another currency or billing cycle (see [app.subsonar.domain.PriceHistoryCalculator.relevant]).
 * Rows are deleted together with their subscription.
 */
@Entity(
    tableName = "price_history",
    foreignKeys = [
        ForeignKey(
            entity = Subscription::class,
            parentColumns = ["id"],
            childColumns = ["subscription_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("subscription_id")],
)
data class PriceChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Long,
    @ColumnInfo(name = "effective_date") val effectiveDate: LocalDate,
    @ColumnInfo(name = "old_price") val oldPrice: BigDecimal,
    @ColumnInfo(name = "new_price") val newPrice: BigDecimal,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    val cycle: BillingCycle,
    @ColumnInfo(name = "recorded_at") val recordedAtMillis: Long = System.currentTimeMillis(),
    val source: PriceChangeSource = PriceChangeSource.MANUAL,
)
