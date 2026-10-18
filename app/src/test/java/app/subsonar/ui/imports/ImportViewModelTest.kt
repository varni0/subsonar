package app.subsonar.ui.imports

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.subsonar.AppContainer
import app.subsonar.data.BillingCycle
import app.subsonar.data.Subscription
import app.subsonar.domain.importer.Corpus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/** End-to-end through the view model with real Room databases: pick files -> preview -> import -> review -> add. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ImportViewModelTest {
    private lateinit var app: Application
    private lateinit var container: AppContainer
    private lateinit var vm: ImportViewModel
    private val dir = java.nio.file.Files.createTempDirectory("imp").toFile()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        container = AppContainer(app)
        vm = ImportViewModel(app, container)
    }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun uri(name: String, bytes: ByteArray): Uri = Uri.fromFile(File(dir, name).apply { writeBytes(bytes) })
    private fun await(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(20)
        }
        fail("timeout waiting for $what; state=${vm.state.value.stage} busy=${vm.state.value.busy}")
    }
    private fun pick(vararg u: Uri) { vm.onFilesPicked(u.toList()); await("preview") { vm.state.value.stage == ImportStage.PREVIEW && !vm.state.value.busy } }
    private fun confirm() { vm.confirmImport(); await("review") { vm.state.value.stage == ImportStage.REVIEW && !vm.state.value.busy } }

    private val ledger = String(Corpus.resource("synthetic/matrix/A01.csv")!!).lines()
    private fun part(from: String, to: String) = (listOf(ledger.first()) + ledger.drop(1).filter { it.isNotBlank() && it.substring(0, 7) >= from && it.substring(0, 7) <= to }).joinToString("\n").toByteArray()

    @Test fun multipleFilesWithABadOneAreImportedIndependently() {
        pick(uri("a.csv", part("2025-01", "2025-09")), uri("bad.csv", "no table here\n".toByteArray()), uri("b.csv", part("2025-07", "2026-06")), uri("empty.csv", ByteArray(0)))
        val files = vm.state.value.files
        assertEquals(listOf(true, false, true, false), files.map { it.usable })
        assertEquals(2, vm.state.value.importable)
        confirm()
        val r = vm.state.value.review!!
        assertEquals(258, container.imports.let { runBlocking { it.allTransactions().size } })
        assertTrue(r.newTransactions > 0 && r.knownTransactions > 0) // the overlap of the two files was recognised
        assertEquals(258, r.newTransactions + 0)
        assertTrue(r.groups.likely.size >= 5); assertEquals(2, r.groups.bills.size)
        assertEquals(r.groups.likely.map { it.key }.toSet(), r.selected.filter { k -> r.groups.likely.any { it.key == k } }.toSet())
        assertFalse("bills are not pre-selected", r.groups.bills.any { it.key in r.selected })
        assertEquals(18, r.basis.months.size)
    }

    @Test fun addingSuggestionsCreatesSubscriptionsAndIsNotRepeated() {
        pick(uri("all.csv", Corpus.resource("synthetic/matrix/A01.csv")!!))
        confirm()
        val r = vm.state.value.review!!
        val selected = r.selected.size
        assertTrue(selected >= 5)
        vm.addSelected(); await("done") { vm.state.value.stage == ImportStage.DONE }
        assertEquals(selected, vm.state.value.addedCount)
        val subs = runBlocking { container.subscriptions.getAll() }
        assertEquals(selected, subs.size)
        val gym = subs.first { it.name.startsWith("Gym") }
        assertEquals(BigDecimal("29.00"), gym.price); assertEquals(BillingCycle.MONTHLY, gym.cycle); assertEquals("gym city", gym.importKey)
        // importing again: every suggestion that was added is now flagged as already tracked and cannot be selected
        vm.reset(); pick(uri("all2.csv", Corpus.resource("synthetic/matrix/A01.csv")!!)); confirm()
        val again = vm.state.value.review!!
        assertEquals(0, again.newTransactions); assertEquals(258, again.knownTransactions)
        assertEquals(subs.map { it.importKey }.toSet(), again.duplicates)
        assertTrue(again.selected.isEmpty())
        vm.toggleCandidate(again.duplicates.first()); assertTrue(vm.state.value.review!!.selected.isEmpty())
        vm.addSelected(); assertEquals(selected, runBlocking { container.subscriptions.getAll() }.size)
    }

    @Test fun existingManualSubscriptionIsNotSuggestedAgain() {
        runBlocking { container.subscriptions.save(Subscription(name = "Musicbox Premium Family", price = BigDecimal("14.99"), currencyCode = "EUR", cycle = BillingCycle.MONTHLY, renewalAnchor = LocalDate.now())) }
        pick(uri("all.csv", Corpus.resource("synthetic/matrix/A01.csv")!!)); confirm()
        val r = vm.state.value.review!!
        assertTrue("musicbox" in r.duplicates); assertFalse("musicbox" in r.selected)
    }

    @Test fun userCorrectionIsRememberedForTheNextImportOfTheSameLayout() {
        pick(uri("a.csv", part("2025-01", "2025-03")))
        val id = vm.state.value.files.single().id
        val m = vm.state.value.files.single().analysis.mapping!!
        vm.openEditor(id); assertEquals(ImportStage.EDIT, vm.state.value.stage)
        vm.updateMapping(id, m.copy(invertSign = true))
        await("reparse") { vm.state.value.files.single().analysis.mapping?.invertSign == true }
        assertTrue(vm.state.value.files.single().analysis.transactions.first().amount.signum() > 0)
        vm.closeEditor(); confirm()
        vm.reset()
        pick(uri("b.csv", part("2025-04", "2025-06")))
        val card = vm.state.value.files.single()
        assertTrue(card.analysis.fromSavedMapping); assertTrue(card.analysis.mapping!!.invertSign)
    }

    @Test fun deletingHistoryKeepsAddedSubscriptions() {
        pick(uri("all.csv", Corpus.resource("synthetic/matrix/A01.csv")!!)); confirm()
        vm.addSelected(); await("done") { vm.state.value.stage == ImportStage.DONE }
        runBlocking { container.imports.deleteHistory() }
        await("coverage cleared") { vm.state.value.coverage.isEmpty }
        assertTrue(runBlocking { container.subscriptions.getAll() }.isNotEmpty())
        assertEquals(0, runBlocking { container.imports.allTransactions() }.size)
    }

    @Test fun zipOfStatementsIsExpandedAndEachFileReported() {
        val bo = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bo).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("jan.csv")); z.write(part("2025-01", "2025-01")); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("readme.txt")); z.write("hi".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("feb.csv")); z.write(part("2025-02", "2025-02")); z.closeEntry()
        }
        pick(uri("statements.zip", bo.toByteArray()))
        val f = vm.state.value.files
        assertEquals(3, f.size); assertEquals(listOf(true, false, true), f.map { it.usable })
        assertTrue(f[0].name.endsWith("jan.csv"))
    }

    @Test fun unreadableUriIsReportedNotThrown() {
        pick(Uri.fromFile(File(dir, "missing.csv")))
        assertFalse(vm.state.value.files.single().usable)
    }
}
