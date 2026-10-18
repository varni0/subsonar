package app.subsonar.data.imports

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ImportDao {
    /** Ignores ids that already exist; the returned row ids are -1 for ignored rows. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactions(items: List<ImportedTransaction>): List<Long>

    @Insert
    suspend fun insertRecord(record: ImportRecord): Long

    @Query("UPDATE import_record SET new_transactions = :added WHERE id = :id")
    suspend fun setNewCount(id: Long, added: Int)

    @Query("SELECT * FROM imported_transaction ORDER BY date, id")
    suspend fun allTransactions(): List<ImportedTransaction>

    @Query("SELECT DISTINCT date FROM imported_transaction ORDER BY date")
    fun observeDates(): Flow<List<LocalDate>>

    @Query("SELECT COUNT(*) FROM imported_transaction")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM imported_transaction")
    suspend fun count(): Int

    @Query("SELECT * FROM import_record ORDER BY imported_at DESC, id DESC")
    fun observeRecords(): Flow<List<ImportRecord>>

    @Query("SELECT * FROM import_record ORDER BY imported_at, id")
    suspend fun records(): List<ImportRecord>

    @Query("DELETE FROM imported_transaction")
    suspend fun deleteTransactions()

    @Query("DELETE FROM import_record")
    suspend fun deleteRecords()

    @Query("SELECT * FROM format_fingerprint")
    suspend fun fingerprints(): List<FormatFingerprint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFingerprint(item: FormatFingerprint)
}
