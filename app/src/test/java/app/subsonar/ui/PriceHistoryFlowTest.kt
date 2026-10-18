package app.subsonar.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.subsonar.AppContainer
import app.subsonar.data.BillingCycle
import app.subsonar.data.PriceChange
import app.subsonar.data.PriceChangeSource
import app.subsonar.data.Subscription
import app.subsonar.domain.recurring.monthly
import app.subsonar.ui.imports.ImportStage
import app.subsonar.ui.imports.ImportViewModel
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
import java.time.YearMonth

/** Price history end to end with real Room databases: manual edits, the repository, and the bank-import offers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PriceHistoryFlowTest {
    private lateinit var app: Application
    private lateinit var container: AppContainer
    private val dir = java.nio.file.Files.createTempDirectory("ph").toFile()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        container = AppContainer(app)
        runBlocking { container.settings.setDefaultCurrency("EUR") } // statements without a currency column use this
    }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun idle(cond: () -> Boolean, what: String) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(20)
        }
        fail("timeout: $what")
    }

    private fun addSub(name: String = "Streamflix HD", price: String = "11.99", cur: String = "EUR", cycle: BillingCycle = BillingCycle.MONTHLY): Long =
        runBlocking { container.subscriptions.save(Subscription(name = name, price = BigDecimal(price), currencyCode = cur, cycle = cycle, renewalAnchor = LocalDate.now().plusDays(5))) }

    private fun editVm(id: Long): EditViewModel {
        val vm = EditViewModel(app, container, SavedStateHandle(mapOf("id" to id)))
        idle({ vm.state.value.loaded }, "edit loaded")
        return vm
    }

    private fun save(vm: EditViewModel) { vm.save(); idle({ vm.state.value.finished }, "saved") }
    private fun history(id: Long) = runBlocking { container.subscriptions.getHistory(id) }

    // ---------------- manual edits ----------------

    @Test fun changingThePriceRecordsAHistoryEntryWithTheChosenDate() {
        val id = addSub()
        val vm = editVm(id)
        assertFalse(vm.state.value.priceChanged)
        val date = LocalDate.now().minusDays(20)
        vm.update { it.copy(price = "13.99", priceEffective = date) }
        assertTrue(vm.state.value.priceChanged)
        save(vm)
        assertEquals(BigDecimal("13.99"), runBlocking { container.subscriptions.get(id) }!!.price)
        val h = history(id).single()
        assertEquals(BigDecimal("11.99"), h.oldPrice); assertEquals(BigDecimal("13.99"), h.newPrice)
        assertEquals(date, h.effectiveDate); assertEquals("EUR", h.currencyCode); assertEquals(BillingCycle.MONTHLY, h.cycle)
        assertEquals(PriceChangeSource.MANUAL, h.source)
    }

    @Test fun effectiveDateDefaultsToToday() {
        val id = addSub()
        val vm = editVm(id)
        vm.update { it.copy(price = "12.99") }
        save(vm)
        assertEquals(LocalDate.now(), history(id).single().effectiveDate)
    }

    @Test fun aFutureEffectiveDateIsClampedToToday() {
        val id = addSub()
        val vm = editVm(id)
        vm.update { it.copy(price = "12.99", priceEffective = LocalDate.now().plusMonths(2)) }
        save(vm)
        assertEquals(LocalDate.now(), history(id).single().effectiveDate)
    }

    @Test fun editingOtherFieldsOrSavingTheSamePriceRecordsNothing() {
        val id = addSub()
        val vm = editVm(id)
        vm.update { it.copy(name = "Streamflix Premium", price = "11.990") }
        assertFalse(vm.state.value.priceChanged)
        save(vm)
        assertTrue(history(id).isEmpty())
    }

    @Test fun changingCurrencyOrCycleTogetherWithThePriceIsNotAPriceHike() {
        val a = addSub(name = "A"); val b = addSub(name = "B")
        editVm(a).also { it.update { s -> s.copy(price = "130", currency = "NOK") }; assertFalse(it.state.value.priceChanged); save(it) }
        editVm(b).also { it.update { s -> s.copy(price = "99", cycle = BillingCycle.YEARLY) }; assertFalse(it.state.value.priceChanged); save(it) }
        assertTrue(history(a).isEmpty()); assertTrue(history(b).isEmpty())
    }

    @Test fun newSubscriptionsHaveNoHistoryAndTheEditScreenShowsTheStoredHistory() {
        val id = addSub()
        runBlocking { container.subscriptions.applyPriceChange(id, PriceChange(subscriptionId = id, effectiveDate = LocalDate.now().minusDays(3), oldPrice = BigDecimal("11.99"), newPrice = BigDecimal("12.99"), currencyCode = "EUR", cycle = BillingCycle.MONTHLY)) }
        val vm = editVm(id)
        idle({ vm.state.value.history.size == 1 }, "history observed")
        assertEquals(BigDecimal("12.99"), vm.state.value.original!!.price)
        assertEquals(BigDecimal("12.99"), vm.state.value.price.toBigDecimal())
    }

    @Test fun historyIsRemovedWithTheSubscription() {
        val id = addSub()
        val vm = editVm(id); vm.update { it.copy(price = "15") }; save(vm)
        assertEquals(1, history(id).size)
        val sub = runBlocking { container.subscriptions.get(id) }!!
        runBlocking { container.subscriptions.delete(sub) }
        assertTrue(history(id).isEmpty())
    }

    @Test fun repositoryAppliesAPriceChangeAtomicallyAndReportsMissingSubscriptions() {
        val id = addSub()
        val ok = runBlocking { container.subscriptions.applyPriceChange(id, PriceChange(subscriptionId = id, effectiveDate = LocalDate.now(), oldPrice = BigDecimal("11.99"), newPrice = BigDecimal("14.99"), currencyCode = "EUR", cycle = BillingCycle.MONTHLY, source = PriceChangeSource.IMPORT)) }
        assertTrue(ok)
        assertEquals(BigDecimal("14.99"), runBlocking { container.subscriptions.get(id) }!!.price)
        assertEquals(PriceChangeSource.IMPORT, history(id).single().source)
        assertFalse(runBlocking { container.subscriptions.applyPriceChange(9999, PriceChange(subscriptionId = 9999, effectiveDate = LocalDate.now(), oldPrice = BigDecimal.ONE, newPrice = BigDecimal.TEN, currencyCode = "EUR", cycle = BillingCycle.MONTHLY)) })
    }

    // ---------------- bank import ----------------

    private fun statement(): ByteArray {
        val start = YearMonth.now().minusMonths(13).toString()
        val next = YearMonth.now().minusMonths(5).toString()
        val rows = monthly(start, 8, 3, "-11.99", "STREAMFLIX.COM 4412") + monthly(next, 5, 3, "-13.99", "STREAMFLIX.COM 8801")
        return ("Date,Description,Amount\n" + rows.joinToString("\n") { "${it.date},${it.description},${it.amount.toPlainString()}" } + "\n").toByteArray()
    }

    private fun runImport(vm: ImportViewModel) {
        val f = File(dir, "bank.csv").apply { writeBytes(statement()) }
        vm.onFilesPicked(listOf(Uri.fromFile(f)))
        idle({ vm.state.value.stage == ImportStage.PREVIEW && !vm.state.value.busy }, "preview")
        vm.confirmImport()
        idle({ vm.state.value.stage == ImportStage.REVIEW && !vm.state.value.busy }, "review")
    }

    @Test fun importOffersToUpdateATrackedSubscriptionAndRecordsTheHike() {
        val id = addSub(name = "Streamflix HD", price = "11.99")
        val vm = ImportViewModel(app, container)
        runImport(vm)
        val r = vm.state.value.review!!
        assertEquals(1, r.offers.size)
        val offer = r.offers.values.single()
        assertEquals(id, offer.subscriptionId); assertEquals(BigDecimal("11.99"), offer.oldPrice); assertEquals(BigDecimal("13.99"), offer.newPrice)
        assertEquals(r.offers.keys, r.selectedUpdates)
        assertTrue(r.selectedCount >= 1)
        vm.addSelected(); idle({ vm.state.value.stage == ImportStage.DONE }, "done")
        assertEquals(1, vm.state.value.updatedCount)
        assertEquals(BigDecimal("13.99"), runBlocking { container.subscriptions.get(id) }!!.price)
        val h = history(id).single()
        assertEquals(PriceChangeSource.IMPORT, h.source); assertEquals(offer.effectiveFrom, h.effectiveDate)
        assertEquals(1, runBlocking { container.subscriptions.getAll() }.size) // no duplicate subscription was created
    }

    @Test fun deselectedOfferChangesNothing() {
        val id = addSub(name = "Streamflix HD", price = "11.99")
        val vm = ImportViewModel(app, container)
        runImport(vm)
        val key = vm.state.value.review!!.offers.keys.single()
        vm.toggleUpdate(key)
        assertTrue(vm.state.value.review!!.selectedUpdates.isEmpty())
        vm.addSelected() // nothing selected: no-op
        assertEquals(BigDecimal("11.99"), runBlocking { container.subscriptions.get(id) }!!.price)
        assertTrue(history(id).isEmpty())
    }

    @Test fun noOfferWhenTheStoredPriceIsAlreadyCurrent() {
        addSub(name = "Streamflix HD", price = "13.99")
        val vm = ImportViewModel(app, container)
        runImport(vm)
        assertTrue(vm.state.value.review!!.offers.isEmpty())
    }

    @Test fun newSubscriptionFromImportGetsItsPriceHistory() {
        val vm = ImportViewModel(app, container)
        runImport(vm)
        val r = vm.state.value.review!!
        assertTrue(r.offers.isEmpty())
        vm.addSelected(); idle({ vm.state.value.stage == ImportStage.DONE }, "done")
        val sub = runBlocking { container.subscriptions.getAll() }.single { it.name.startsWith("Streamflix", true) }
        assertEquals(BigDecimal("13.99"), sub.price)
        val h = history(sub.id).single()
        assertEquals(BigDecimal("11.99"), h.oldPrice); assertEquals(BigDecimal("13.99"), h.newPrice); assertEquals(PriceChangeSource.IMPORT, h.source)
        assertEquals(0, vm.state.value.updatedCount)
    }
}
