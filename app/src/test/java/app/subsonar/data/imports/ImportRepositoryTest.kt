package app.subsonar.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.subsonar.domain.importer.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ImportRepositoryTest {
    private lateinit var db: ImportDatabase
    private lateinit var repo: ImportRepository
    private var now = 1_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ImportDatabase::class.java).allowMainThreadQueries().build()
        repo = ImportRepository(db) { now++ }
    }
    @After fun tearDown() = db.close()

    private fun tx(d: String, a: String, t: String) = ParsedTransaction(LocalDate.parse(d), BigDecimal(a), "EUR", t)
    private fun file(name: String, rows: List<ParsedTransaction>, fp: String = "fp1", m: ColumnMapping? = null) = ImportedFile(name, fp, m, rows, rows.size)

    @Test fun overlappingFilesAreMergedWithoutDuplicates() = runBlocking {
        val all = (1..60).map { tx("2025-01-%02d".format((it - 1) % 28 + 1).replace("2025-01", "2025-" + "%02d".format((it - 1) / 28 + 1)), "-${it}.00", "SHOP $it") }
        val r = repo.commit(listOf(file("a.csv", all.take(40)), file("b.csv", all.drop(20))))
        assertEquals(40, r[0].added); assertEquals(0, r[0].duplicates)
        assertEquals(20, r[1].added); assertEquals(20, r[1].duplicates)
        assertEquals(60, repo.allTransactions().size)
    }
    @Test fun importingTheSameFileTwiceAddsNothing() = runBlocking {
        val rows = listOf(tx("2025-01-05", "-4.50", "COFFEE"), tx("2025-01-05", "-4.50", "COFFEE"), tx("2025-01-06", "-9.00", "LUNCH"))
        assertEquals(3, repo.commit(listOf(file("a", rows))).single().added)
        val again = repo.commit(listOf(file("a", rows))).single()
        assertEquals(0, again.added); assertEquals(3, again.duplicates)
        assertEquals(3, repo.allTransactions().size)
        // two genuinely identical same-day purchases are kept as two rows
        assertEquals(2, repo.allTransactions().count { it.description == "COFFEE" })
    }
    @Test fun importRecordsStoreFileNameRowsAndDateRange() = runBlocking {
        repo.commit(listOf(file("jan.csv", listOf(tx("2025-01-31", "-1", "A"), tx("2025-01-02", "-2", "B")))))
        val rec = repo.records.first().single()
        assertEquals("jan.csv", rec.fileName); assertEquals(2, rec.rowsRead); assertEquals(2, rec.newTransactions)
        assertEquals(LocalDate.of(2025, 1, 2), rec.dateFrom); assertEquals(LocalDate.of(2025, 1, 31), rec.dateTo)
    }
    @Test fun coverageListsMonthsAndGaps() = runBlocking {
        repo.commit(listOf(file("x", listOf(tx("2025-01-31", "-1", "A"), tx("2025-02-02", "-2", "B"), tx("2025-05-02", "-2", "C"), tx("2025-05-03", "-2", "D")))))
        val c = repo.coverageNow()
        assertEquals(listOf(YearMonth.of(2025, 1), YearMonth.of(2025, 2), YearMonth.of(2025, 5)), c.months)
        assertEquals(4, c.transactionCount)
        assertEquals(listOf(YearMonth.of(2025, 3), YearMonth.of(2025, 4)), c.gaps)
        assertEquals(2, c.ranges.size)
    }
    @Test fun deleteHistoryRemovesTransactionsAndRecordsButKeepsMappings() = runBlocking {
        val m = ColumnMapping(0, "yyyy-MM-dd", listOf(1), amountColumn = 2, decimalSeparator = '.')
        repo.commit(listOf(file("x", listOf(tx("2025-01-31", "-1", "A")), "fpX", m)))
        assertEquals(1, repo.loadMappings().size)
        repo.deleteHistory()
        assertEquals(0, repo.allTransactions().size); assertTrue(repo.records.first().isEmpty()); assertTrue(repo.coverageNow().isEmpty)
        assertEquals(m, repo.loadMappings()["fpX"])
        // and the same file can be imported again afterwards
        assertEquals(1, repo.commit(listOf(file("x", listOf(tx("2025-01-31", "-1", "A"))))).single().added)
    }
    @Test fun mappingsAreStoredByFingerprintAndUpdated() = runBlocking {
        val m1 = ColumnMapping(0, "yyyy-MM-dd", listOf(1), amountColumn = 2, decimalSeparator = '.')
        val m2 = m1.copy(decimalSeparator = ',', invertSign = true)
        repo.commit(listOf(file("x", listOf(tx("2025-01-31", "-1", "A")), "fpX", m1)))
        repo.commit(listOf(file("y", listOf(tx("2025-02-01", "-1", "B")), "fpX", m2), file("z", listOf(tx("2025-02-02", "-1", "C")), "fpZ", m1)))
        val all = repo.loadMappings()
        assertEquals(setOf("fpX", "fpZ"), all.keys); assertEquals(m2, all["fpX"])
    }
    @Test fun largeHistoryIsHandled() = runBlocking {
        val rows = (0 until 20_000).map { tx(LocalDate.of(2020, 1, 1).plusDays((it % 2000).toLong()).toString(), "-${it % 97 + 1}.00", "MERCHANT ${it}") }
        val t0 = System.nanoTime()
        assertEquals(20_000, repo.commit(listOf(file("big", rows))).single().added)
        assertEquals(20_000, repo.allTransactions().size)
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000}ms", (System.nanoTime() - t0) / 1_000_000 < 30_000)
    }
    @Test fun importedHistoryFeedsTheDetector() = runBlocking {
        val rows = (0 until 8).map { tx(LocalDate.of(2025, 1, 5).plusMonths(it.toLong()).toString(), "-9.99", "SPOTIFY AB") }
        repo.commit(listOf(file("m", rows)))
        val found = app.subsonar.domain.recurring.RecurringDetector.detect(repo.allTransactions(), LocalDate.of(2025, 9, 1))
        assertEquals("spotify", found.single().key.substringBefore(' '))
    }
}
