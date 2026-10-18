package app.subsonar.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Subscription>>

    @Query("SELECT * FROM subscriptions")
    suspend fun getAll(): List<Subscription>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: Long): Subscription?

    @Query("SELECT COUNT(*) FROM subscriptions")
    suspend fun count(): Int

    @Insert
    suspend fun insert(subscription: Subscription): Long

    @Update
    suspend fun update(subscription: Subscription)

    @Delete
    suspend fun delete(subscription: Subscription)

    @Query("UPDATE subscriptions SET last_reminded_for = :date WHERE id = :id")
    suspend fun markReminded(id: Long, date: LocalDate)

    // ---- price history ----

    @Query("SELECT * FROM price_history WHERE subscription_id = :subscriptionId ORDER BY effective_date, id")
    fun observeHistory(subscriptionId: Long): Flow<List<PriceChange>>

    @Query("SELECT * FROM price_history ORDER BY effective_date, id")
    fun observeAllHistory(): Flow<List<PriceChange>>

    @Query("SELECT * FROM price_history WHERE subscription_id = :subscriptionId ORDER BY effective_date, id")
    suspend fun getHistory(subscriptionId: Long): List<PriceChange>

    @Insert
    suspend fun insertPriceChange(change: PriceChange): Long
}
