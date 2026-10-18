package app.subsonar.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class SubscriptionRepository(private val dao: SubscriptionDao, private val db: RoomDatabase? = null) {
    val subscriptions: Flow<List<Subscription>> = dao.observeAll()

    /** Price history of every subscription (for the list badge and the Insights tab). */
    val priceHistory: Flow<List<PriceChange>> = dao.observeAllHistory()

    fun history(subscriptionId: Long): Flow<List<PriceChange>> = dao.observeHistory(subscriptionId)

    suspend fun getAll(): List<Subscription> = dao.getAll()
    suspend fun get(id: Long): Subscription? = dao.getById(id)
    suspend fun count(): Int = dao.count()
    suspend fun getHistory(subscriptionId: Long): List<PriceChange> = dao.getHistory(subscriptionId)

    /**
     * Inserts or updates [subscription]. [changes] (price history entries) are written in the same transaction; for a new
     * subscription their `subscriptionId` is replaced by the new row id.
     */
    suspend fun save(subscription: Subscription, changes: List<PriceChange> = emptyList()): Long =
        inTransaction {
            val id = if (subscription.id == 0L) dao.insert(subscription) else {
                dao.update(subscription); subscription.id
            }
            changes.forEach { dao.insertPriceChange(it.copy(id = 0, subscriptionId = id)) }
            id
        }

    /** Sets a new price on an existing subscription and records the change. Returns false if the subscription is gone. */
    suspend fun applyPriceChange(subscriptionId: Long, change: PriceChange): Boolean = inTransaction {
        val sub = dao.getById(subscriptionId) ?: return@inTransaction false
        dao.update(sub.copy(price = change.newPrice))
        dao.insertPriceChange(change.copy(id = 0, subscriptionId = subscriptionId))
        true
    }

    suspend fun delete(subscription: Subscription) = dao.delete(subscription)
    suspend fun markReminded(id: Long, renewal: LocalDate) = dao.markReminded(id, renewal)

    private suspend fun <T> inTransaction(block: suspend () -> T): T = db?.withTransaction { block() } ?: block()
}
