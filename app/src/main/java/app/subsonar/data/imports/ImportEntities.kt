package app.subsonar.data.imports

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDate

/** One imported bank transaction. [id] is the stable dedupe id (see [app.subsonar.domain.importer.Dedupe]). */
@Entity(tableName = "imported_transaction", indices = [Index("date"), Index("import_id")])
data class ImportedTransaction(
    @PrimaryKey val id: String,
    val date: LocalDate,
    val amount: BigDecimal,
    @ColumnInfo(name = "currency_code") val currencyCode: String?,
    val description: String,
    @ColumnInfo(name = "import_id") val importId: Long,
)

/** One imported file. */
@Entity(tableName = "import_record")
data class ImportRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "imported_at") val importedAtMillis: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "rows_read") val rowsRead: Int,
    @ColumnInfo(name = "transactions") val transactions: Int,
    @ColumnInfo(name = "new_transactions") val newTransactions: Int,
    @ColumnInfo(name = "date_from") val dateFrom: LocalDate?,
    @ColumnInfo(name = "date_to") val dateTo: LocalDate?,
    val fingerprint: String,
)

/** A column mapping the user confirmed for a file layout, reused when the same layout is imported again. */
@Entity(tableName = "format_fingerprint")
data class FormatFingerprint(
    @PrimaryKey val fingerprint: String,
    val mapping: String,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
)
