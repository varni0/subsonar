package app.subsonar.data.imports

import androidx.room.withTransaction
import app.subsonar.domain.importer.ColumnMapping
import app.subsonar.domain.importer.Coverage
import app.subsonar.domain.importer.Dedupe
import app.subsonar.domain.importer.ParsedTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** What the user confirmed for one file. */
class ImportedFile(
    val name: String,
    val fingerprint: String,
    val mapping: ColumnMapping?,
    val transactions: List<ParsedTransaction>,
    val rowsRead: Int,
)

data class FileImportResult(val name: String, val transactions: Int, val added: Int) {
    val duplicates get() = transactions - added
}

class ImportRepository(private val db: ImportDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.importDao()

    val coverage: Flow<Coverage> = combine(dao.observeDates(), dao.observeCount()) { dates, n -> Coverage.of(dates, n) }
    val records: Flow<List<ImportRecord>> = dao.observeRecords()
    val transactionCount: Flow<Int> = dao.observeCount()

    suspend fun loadMappings(): Map<String, ColumnMapping> =
        dao.fingerprints().mapNotNull { f -> ColumnMapping.decode(f.mapping)?.let { f.fingerprint to it } }.toMap()

    suspend fun allTransactions(): List<ParsedTransaction> =
        dao.allTransactions().map { ParsedTransaction(it.date, it.amount, it.currencyCode, it.description) }

    suspend fun coverageNow(): Coverage = coverage.first()

    /**
     * Stores the files atomically. Rows whose stable id already exists (earlier import, overlapping export) are skipped.
     * Confirmed mappings are remembered by fingerprint.
     */
    suspend fun commit(files: List<ImportedFile>): List<FileImportResult> = db.withTransaction {
        val now = clock()
        files.map { f ->
            val withIds = Dedupe.withIds(f.transactions)
            val recordId = dao.insertRecord(
                ImportRecord(
                    importedAtMillis = now, fileName = f.name, rowsRead = f.rowsRead, transactions = withIds.size, newTransactions = 0,
                    dateFrom = f.transactions.minOfOrNull { it.date }, dateTo = f.transactions.maxOfOrNull { it.date }, fingerprint = f.fingerprint,
                ),
            )
            val ids = dao.insertTransactions(withIds.map { (id, t) -> ImportedTransaction(id, t.date, t.amount, t.currency, t.description, recordId) })
            val added = ids.count { it != -1L }
            dao.setNewCount(recordId, added)
            f.mapping?.let { dao.upsertFingerprint(FormatFingerprint(f.fingerprint, it.encode(), now)) }
            FileImportResult(f.name, withIds.size, added)
        }
    }

    /** Deletes imported transactions and import records. Remembered column mappings (no personal data) are kept. */
    suspend fun deleteHistory() = db.withTransaction {
        dao.deleteTransactions()
        dao.deleteRecords()
    }
}
