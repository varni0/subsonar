package app.subsonar.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.subsonar.AppContainer
import app.subsonar.SubsonarApp
import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import app.subsonar.data.PriceChange
import app.subsonar.data.Subscription
import app.subsonar.data.ThemeMode
import app.subsonar.domain.importer.Coverage
import app.subsonar.domain.importer.InputFile
import app.subsonar.domain.importer.StatementImporter
import app.subsonar.domain.recurring.PriceUpdateOffer
import app.subsonar.domain.recurring.RecurringDetector
import app.subsonar.domain.recurring.Suggestions
import app.subsonar.domain.recurring.monthly
import app.subsonar.ui.edit.EditScreen
import app.subsonar.ui.imports.FileCard
import app.subsonar.ui.imports.ImportActions
import app.subsonar.ui.imports.ImportScreen
import app.subsonar.ui.imports.ImportStage
import app.subsonar.ui.imports.ImportUiState
import app.subsonar.ui.imports.ReviewState
import app.subsonar.ui.insights.InsightsScreen
import app.subsonar.ui.list.SubscriptionListScreen
import app.subsonar.ui.settings.SettingsScreen
import app.subsonar.ui.theme.SubsonarTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * Renders the main screens in the languages with the longest or widest text (German, Finnish, Greek, Ukrainian) at a
 * phone width of 411dp and a large font scale, using Robolectric (no emulator). PNGs go to build/reports/l10n-shots/<lang>/
 * (or -Dshots.out=DIR). The test fails when a screen renders blank or a text is cut off (ellipsized / clipped) that
 * is not on the explicit allow-list of texts that are shortened on purpose (file names, one-line previews).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = LocalizedScreenshotsTest.TestApp::class)
class LocalizedScreenshotsTest {
    class TestApp : SubsonarApp() {
        override fun scheduleBackgroundWork() { androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(this) }
    }

    @get:Rule val rule = createComposeRule()
    private val out = File(System.getProperty("shots.out") ?: "build/reports/l10n-shots").apply { mkdirs() }
    private val findings = mutableListOf<String>()

    private fun settle() { repeat(6) { rule.waitForIdle(); Thread.sleep(40) } }

    private fun setLanguage(tag: String, fontScale: Float) {
        RuntimeEnvironment.setQualifiers("$tag-w411dp-h1300dp-notnight-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        Locale.setDefault(Locale.forLanguageTag(tag))
    }

    /** Texts that are legitimately shortened (a few characters of a raw file name or of a bank text). */
    private fun allowed(text: String) = text.contains(".csv") || text.contains(".tsv") || text.startsWith("20")

    private fun cutOffTexts(): List<String> {
        val bad = mutableListOf<String>()
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        for (n in nodes) {
            val results = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val r = results.firstOrNull() ?: continue
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: continue
            val ellipsized = (0 until r.lineCount).any { r.isLineEllipsized(it) }
            if (ellipsized && !allowed(text)) bad += text
        }
        return bad
    }

    private fun shot(lang: String, name: String) {
        settle()
        val dir = File(out, lang).apply { mkdirs() }
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val seen = HashSet<Int>()
        for (y in 0 until bmp.height step 9) for (x in 0 until bmp.width step 9) seen.add(bmp.getPixel(x, y))
        assertTrue("$lang/$name looks blank", seen.size > 8)
        cutOffTexts().forEach { findings += "$lang/$name: \"$it\"" }
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>() as SubsonarApp
    private val container: AppContainer get() = app.container

    private fun seed(): Long = runBlocking {
        container.settings.setDefaultCurrency("EUR")
        val today = LocalDate.now()
        fun sub(name: String, price: String, cycle: BillingCycle, cat: Category, days: Long) =
            Subscription(name = name, price = BigDecimal(price), currencyCode = "EUR", cycle = cycle, renewalAnchor = today.plusDays(days), category = cat)
        val stream = container.subscriptions.save(sub("Streamflix", "13.99", BillingCycle.MONTHLY, Category.STREAMING, 3))
        val music = container.subscriptions.save(sub("Spotify", "17.99", BillingCycle.MONTHLY, Category.MUSIC, 11))
        container.subscriptions.save(sub("Stadtwerke", "89.00", BillingCycle.MONTHLY, Category.UTILITIES, 20))
        container.subscriptions.save(sub("Fitnessclub", "480.00", BillingCycle.YEARLY, Category.FITNESS, 140))
        fun hike(id: Long, from: String, to: String, daysAgo: Long) = PriceChange(subscriptionId = id, effectiveDate = today.minusDays(daysAgo), oldPrice = BigDecimal(from), newPrice = BigDecimal(to), currencyCode = "EUR", cycle = BillingCycle.MONTHLY)
        container.subscriptions.applyPriceChange(stream, hike(stream, "9.99", "11.99", 300).copy(newPrice = BigDecimal("11.99")))
        container.subscriptions.applyPriceChange(stream, hike(stream, "11.99", "13.99", 40))
        container.subscriptions.applyPriceChange(music, hike(music, "15.99", "17.99", 120))
        stream
    }

    private fun content(block: @androidx.compose.runtime.Composable () -> Unit) =
        rule.setContent { SubsonarTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { block() } }

    private fun idleUntilLoaded() { repeat(20) { settle() } }

    @Test fun german() = run("de")
    @Test fun finnish() = run("fi")
    @Test fun greek() = run("el")
    @Test fun ukrainian() = run("uk")
    @Test fun englishBaseline() = run("en")

    private fun run(tag: String) {
        setLanguage(tag, 1.3f)
        val streamId = seed()
        val lang = tag
        val home = HomeViewModel(container)
        val base = ImportUiState()

        // Subscriptions list (price-up badge) -> Insights (price increases)
        val screenState = androidx.compose.runtime.mutableStateOf(0)
        val edit by lazy { EditViewModel(app, container, SavedStateHandle(mapOf("id" to streamId))) }
        val settings = SettingsViewModel(app, container)
        val importState = androidx.compose.runtime.mutableStateOf(base)
        content {
            when (screenState.value) {
                0 -> SubscriptionListScreen(home, onAdd = {}, onOpen = {}, onImport = {})
                1 -> InsightsScreen(home)
                2 -> EditScreen(onDone = {}, viewModel = edit)
                3 -> SettingsScreen(viewModel = settings)
                else -> ImportScreen(importState.value, ImportActions())
            }
        }
        idleUntilLoaded(); shot(lang, "01_list")
        screenState.value = 1; idleUntilLoaded(); shot(lang, "02_insights")
        screenState.value = 2; idleUntilLoaded()
        edit.update { it.copy(price = "15.49") } // pending price change shows the "applies from" field
        repeat(100) { if (edit.state.value.history.size >= 2) return@repeat; Thread.sleep(50); rule.waitForIdle() }
        assertTrue("price history not loaded", edit.state.value.history.size >= 2)
        idleUntilLoaded(); shot(lang, "03_edit_price_history")
        screenState.value = 3; idleUntilLoaded(); shot(lang, "04_settings")

        // Import: review with a price-update offer, and the preview
        val txs = (monthly(YearMonth.now().minusMonths(13).toString(), 8, 3, "-11.99", "STREAMFLIX.COM 4412") + monthly(YearMonth.now().minusMonths(5).toString(), 5, 3, "-13.99", "STREAMFLIX.COM 8801") +
            monthly(YearMonth.now().minusMonths(13).toString(), 13, 15, "-9.99", "MUSIKBOX PREMIUM"))
        val groups = Suggestions.group(RecurringDetector.detect(txs, LocalDate.now(), "EUR"))
        val all = groups.likely + groups.bills + groups.low
        val offer = PriceUpdateOffer(1, "Streamflix", BigDecimal("11.99"), BigDecimal("13.99"), "EUR", BillingCycle.MONTHLY, LocalDate.now().minusMonths(5))
        val first = all.first().key
        val review = ReviewState(groups, emptySet(), all.map { it.key }.toSet(), Coverage.of(txs.map { it.date }, txs.size), 40, 3, mapOf(first to offer), setOf(first))
        importState.value = ImportUiState(stage = ImportStage.REVIEW, review = review); screenState.value = 4
        idleUntilLoaded(); shot(lang, "05_import_review")
        val good = FileCard(1, StatementImporter.analyze(InputFile("export-2025-lang-deutsch-sparkasse.csv", ("Buchungstag;Verwendungszweck;Betrag\n" + txs.take(6).joinToString("\n") { "${it.date};${it.description};${it.amount.toPlainString().replace('.', ',')}" } + "\n").toByteArray())))
        importState.value = ImportUiState(stage = ImportStage.PREVIEW, files = listOf(good, good.copy(id = 2)))
        idleUntilLoaded(); shot(lang, "06_import_preview")
        importState.value = ImportUiState(coverage = Coverage.of(txs.map { it.date }, txs.size))
        idleUntilLoaded(); shot(lang, "07_import_intro")

        File(out, "$lang-overflow.txt").writeText(findings.joinToString("\n"))
        assertTrue("Texts cut off in $tag:\n" + findings.joinToString("\n"), findings.isEmpty())
    }
}
