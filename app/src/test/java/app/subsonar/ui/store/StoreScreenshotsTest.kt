package app.subsonar.ui.store

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.subsonar.AppContainer
import app.subsonar.R
import app.subsonar.SubsonarApp
import app.subsonar.data.BillingCycle
import app.subsonar.data.Category
import app.subsonar.data.PriceChange
import app.subsonar.data.Subscription
import app.subsonar.data.ThemeMode
import app.subsonar.domain.importer.Coverage
import app.subsonar.domain.recurring.RecurringDetector
import app.subsonar.domain.recurring.Suggestions
import app.subsonar.domain.recurring.monthly
import app.subsonar.ui.EditViewModel
import app.subsonar.ui.HomeViewModel
import app.subsonar.ui.SettingsViewModel
import app.subsonar.ui.components.LocalProductName
import app.subsonar.ui.edit.AppearancePanel
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import app.subsonar.ui.edit.EditScreen
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
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * Raw screens for the framed Play Store screenshots (tools/store-assets/generate.py frames them).
 * Opt-in, not part of the normal test run:
 *   ./gradlew testDebugUnitTest --tests '*StoreScreenshotsTest*' -Pstore.shots=/workspace/store-assets-v2/raw [-Pstore.name=Subsonar]
 * Renders 1080 px wide (360dp at xxhdpi) with fictional services and prices, per language: en (EUR), nb (NOK), de (EUR).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = StoreScreenshotsTest.TestApp::class)
class StoreScreenshotsTest {
    class TestApp : SubsonarApp() {
        override fun scheduleBackgroundWork() { androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(this) }
    }

    @get:Rule val rule = createComposeRule()
    private val outDir = System.getProperty("store.shots")
    private val productName = System.getProperty("store.name")?.takeIf { it.isNotBlank() }

    private val app get() = ApplicationProvider.getApplicationContext<Application>() as SubsonarApp
    private val container: AppContainer get() = app.container

    private data class Sample(val name: String, val price: String, val cycle: BillingCycle, val cat: Category, val days: Long, val color: String? = null, val avatar: String? = null)
    private data class Lang(val currency: String, val items: List<Sample>, val hikes: List<Triple<Int, String, String>>, val merchants: List<Pair<String, String>>)

    private val m = BillingCycle.MONTHLY
    private val langs = mapOf(
        "en" to Lang("EUR", listOf(
            Sample("Streamflix", "13.99", m, Category.STREAMING, 2), Sample("Tunewave Premium", "11.99", m, Category.MUSIC, 5),
            Sample("Northcloud 2 TB", "9.99", m, Category.CLOUD, 6), Sample("Fjordfit Gym", "39.00", m, Category.FITNESS, 11),
            Sample("Daily Ledger", "12.00", m, Category.NEWS, 15), Sample("Mobile plan", "25.00", m, Category.PHONE_INTERNET, 20),
            Sample("Home insurance", "18.50", m, Category.INSURANCE, 26, color = "aurora", avatar = "icon:home"),
            Sample("City bike pass", "49.00", BillingCycle.YEARLY, Category.OTHER, 140),
        ), listOf(Triple(0, "9.99", "11.99"), Triple(0, "11.99", "13.99"), Triple(1, "10.99", "11.99")),
            listOf("STREAMFLIX.COM 4412" to "-13.99", "TUNEWAVE PREMIUM" to "-11.99", "FJORDFIT GYM AS" to "-39.00")),
        "nb" to Lang("NOK", listOf(
            Sample("Strømmeflix", "149", m, Category.STREAMING, 2), Sample("Lydbølge Premium", "129", m, Category.MUSIC, 5),
            Sample("Nordsky 2 TB", "99", m, Category.CLOUD, 6), Sample("Fjordtrening", "449", m, Category.FITNESS, 11),
            Sample("Dagsavisa Digital", "249", m, Category.NEWS, 15), Sample("Mobilabonnement", "349", m, Category.PHONE_INTERNET, 20),
            Sample("Innboforsikring", "189", m, Category.INSURANCE, 26, color = "aurora", avatar = "icon:home"),
            Sample("Bysykkel årskort", "499", BillingCycle.YEARLY, Category.OTHER, 140),
        ), listOf(Triple(0, "119", "139"), Triple(0, "139", "149"), Triple(1, "119", "129")),
            listOf("STROMMEFLIX.COM 4412" to "-149.00", "LYDBOLGE PREMIUM" to "-129.00", "FJORDTRENING AS" to "-449.00")),
        "de" to Lang("EUR", listOf(
            Sample("Streamflix", "13.99", m, Category.STREAMING, 2), Sample("Klangwelle Premium", "11.99", m, Category.MUSIC, 5),
            Sample("Wolkenspeicher 2 TB", "9.99", m, Category.CLOUD, 6), Sample("Fjordfit Studio", "34.90", m, Category.FITNESS, 11),
            Sample("Tagesblatt Digital", "14.99", m, Category.NEWS, 15), Sample("Handyvertrag", "19.99", m, Category.PHONE_INTERNET, 20),
            Sample("Hausratversicherung", "6.90", m, Category.INSURANCE, 26, color = "aurora", avatar = "icon:home"),
            Sample("Stadtrad Jahrespass", "49.00", BillingCycle.YEARLY, Category.OTHER, 140),
        ), listOf(Triple(0, "9.99", "11.99"), Triple(0, "11.99", "13.99"), Triple(1, "10.99", "11.99")),
            listOf("STREAMFLIX.COM 4412" to "-13.99", "KLANGWELLE PREMIUM" to "-11.99", "FJORDFIT STUDIO GMBH" to "-34.90")),
    )

    // Tolerates a busy main looper on a loaded machine (AppNotIdleException): a later pass catches up before the capture.
    private fun settle() { repeat(25) { runCatching { rule.waitForIdle() }; Thread.sleep(40) } }

    private fun save(bmp: Bitmap, lang: String, name: String) {
        val dir = File(outDir, lang).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val seen = HashSet<Int>()
        for (y in 0 until bmp.height step 11) for (x in 0 until bmp.width step 11) seen.add(bmp.getPixel(x, y))
        assertTrue("$lang/$name looks blank", seen.size > 8)
    }

    private fun shot(lang: String, name: String) { settle(); save(rule.onRoot().captureToImage().asAndroidBitmap(), lang, name) }

    @Test fun english() = run("en")
    @Test fun norwegian() = run("nb")
    @Test fun german() = run("de")

    /** Bottom bar like the app's navigation host (Navigation.kt), so tab screens look as on a phone. */
    @Composable
    private fun WithTabs(selected: Int, content: @Composable () -> Unit) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf(R.string.tab_subscriptions to R.drawable.ic_list, R.string.tab_insights to R.drawable.ic_insights, R.string.tab_settings to R.drawable.ic_settings)
                    .forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(selected = i == selected, onClick = {}, icon = { Icon(painterResource(icon), null) }, label = { Text(stringResource(label)) })
                    }
            }
        }) { p -> Column(Modifier.fillMaxSize().padding(bottom = p.calculateBottomPadding())) { content() } }
    }

    private fun run(tag: String) {
        assumeTrue("set -Pstore.shots=DIR to render store screenshots", outDir != null)
        val lang = langs.getValue(tag)
        RuntimeEnvironment.setQualifiers("$tag-w360dp-h760dp-notnight-xxhdpi")
        RuntimeEnvironment.setFontScale(1.0f)
        Locale.setDefault(Locale.forLanguageTag(tag))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { container.settings.setDefaultCurrency(lang.currency) }

        val home = HomeViewModel(container)
        val settings = SettingsViewModel(app, container)
        val screen = mutableStateOf(0)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val importState = mutableStateOf(ImportUiState())
        var edit: EditViewModel? = null
        rule.setContent {
            CompositionLocalProvider(LocalProductName provides productName) {
                SubsonarTheme(themeMode = theme.value, dynamicColor = false) {
                    when (screen.value) {
                        0 -> WithTabs(0) { SubscriptionListScreen(home, onAdd = {}, onOpen = {}, onImport = {}) }
                        1 -> WithTabs(1) { InsightsScreen(home) }
                        2 -> WithTabs(2) { SettingsScreen(viewModel = settings) }
                        3 -> EditScreen(onDone = {}, viewModel = edit!!)
                        4 -> ImportScreen(importState.value, ImportActions())
                        5 -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { // appearance picker over the edit screen (inline: Robolectric can't capture dialog windows)
                            EditScreen(onDone = {}, viewModel = edit!!)
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f)))
                            val s by edit!!.state.collectAsState()
                            AppearancePanel(s.name, s.category, "lingonberry", "icon:streaming", onConfirm = { _, _ -> }, onDismiss = {},
                                modifier = Modifier.align(androidx.compose.ui.Alignment.Center).padding(horizontal = 20.dp, vertical = 40.dp))
                        }
                    }
                }
            }
        }
        // 1. Empty state: bank import first
        shot(tag, "01_empty")

        // Seed fictional data
        val today = LocalDate.now()
        val ids = runBlocking {
            lang.items.map { s ->
                container.subscriptions.save(Subscription(name = s.name, price = BigDecimal(s.price), currencyCode = lang.currency, cycle = s.cycle,
                    renewalAnchor = today.plusDays(s.days), category = s.cat, colorKey = s.color, avatar = s.avatar))
            }.also { ids ->
                var ago = 330L
                for ((idx, from, to) in lang.hikes) {
                    container.subscriptions.applyPriceChange(ids[idx], PriceChange(subscriptionId = ids[idx], effectiveDate = today.minusDays(ago), oldPrice = BigDecimal(from),
                        newPrice = BigDecimal(to), currencyCode = lang.currency, cycle = BillingCycle.MONTHLY))
                    ago -= 120
                }
            }
        }
        settle()
        shot(tag, "03_list")
        screen.value = 1; shot(tag, "05_insights")
        screen.value = 2; shot(tag, "07_settings")

        edit = EditViewModel(app, container, SavedStateHandle(mapOf("id" to ids[0])))
        repeat(100) { if (edit!!.state.value.loaded && edit!!.state.value.history.size >= 2) return@repeat; Thread.sleep(50); rule.waitForIdle() }
        screen.value = 3; settle()
        // Show the price-history timeline: scroll the form to its end.
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToIndexOrEnd()
        shot(tag, "04_price_history")

        screen.value = 5; settle()
        shot(tag, "06_appearance")

        // 2. Import review with fictional bank lines
        val txs = lang.merchants.flatMapIndexed { i, (text, amount) -> monthly(YearMonth.now().minusMonths(7).toString(), 7, 3 + i * 5, amount, text, cur = lang.currency) }
        val groups = Suggestions.group(RecurringDetector.detect(txs, today, lang.currency))
        val all = groups.likely + groups.bills + groups.low
        importState.value = ImportUiState(stage = ImportStage.REVIEW, review = ReviewState(groups, emptySet(), all.map { it.key }.toSet(), Coverage.of(txs.map { it.date }, txs.size), txs.size, 2))
        screen.value = 4; shot(tag, "02_import_review")

        // 8. Dark list
        screen.value = 0; theme.value = ThemeMode.DARK; shot(tag, "08_list_dark")
    }
}

/** Scrolls a vertically scrollable node to its end (the edit form is a plain verticalScroll Column). */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToIndexOrEnd() {
    val node = fetchSemanticsNode()
    val range = node.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
    val action = node.config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy].action!!
    action(0f, range.maxValue() * 4)
}
