package app.subsonar.ui.imports

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.subsonar.data.ThemeMode
import app.subsonar.domain.importer.Corpus
import app.subsonar.domain.importer.Coverage
import app.subsonar.domain.importer.InputFile
import app.subsonar.domain.importer.StatementImporter
import app.subsonar.domain.recurring.ExistingSubscription
import app.subsonar.domain.recurring.RecurringDetector
import app.subsonar.domain.recurring.Suggestions
import app.subsonar.ui.theme.SubsonarTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Renders the import screens with Robolectric (no emulator) into build/reports/import-shots (or -Pshots.out=DIR).
 * The test fails if a screen renders blank, and looks for key texts; the PNGs are for visual inspection.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-rUS-w411dp-h891dp-notnight-420dpi")
class ImportScreenshotsTest {
    @get:Rule val rule = createComposeRule()
    private val out = File(System.getProperty("shots.out") ?: "build/reports/import-shots").apply { mkdirs() }

    private fun file(path: String) = StatementImporter.analyze(InputFile(path.substringAfterLast('/'), Corpus.resource(path)!!))
    private fun settle() { repeat(5) { rule.waitForIdle(); Thread.sleep(50) } }

    private fun shot(name: String): Bitmap {
        settle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // not blank: at least a few distinct colours
        val seen = HashSet<Int>()
        for (y in 0 until bmp.height step 7) for (x in 0 until bmp.width step 7) seen.add(bmp.getPixel(x, y))
        assertTrue("$name looks blank", seen.size > 8)
        return bmp
    }

    private val ui = androidx.compose.runtime.mutableStateOf(ImportUiState())
    private val darkTheme = androidx.compose.runtime.mutableStateOf(false)
    private var started = false

    private fun show(state: ImportUiState, dark: Boolean = false) {
        if (!started) {
            started = true
            rule.setContent { SubsonarTheme(themeMode = if (darkTheme.value) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) { ImportScreen(ui.value, ImportActions()) } }
        }
        darkTheme.value = dark
        ui.value = state
        settle()
    }

    @Test fun renderAllImportScreens() {
        val good = FileCard(1, file("synthetic/matrix/A01.csv"))
        val tab = FileCard(2, file("AT/raiffeisen/raiffeisen-elba-tab-2017.tsv"))
        val bad = FileCard(3, StatementImporter.analyze(InputFile("notes.csv", "hello\nthis is no table\n".toByteArray())))
        val empty = FileCard(4, StatementImporter.analyze(InputFile("empty.csv", ByteArray(0))))
        val coverage = Coverage.of(listOf("2025-01-10", "2025-02-11", "2025-03-04", "2025-06-01").map(LocalDate::parse), 258)

        show(ImportUiState(coverage = coverage))
        rule.onNodeWithText("Choose files").assertExists()
        shot("01_intro")

        show(ImportUiState(stage = ImportStage.PREVIEW, files = listOf(good, tab, bad, empty)))
        rule.onNodeWithText("Import and find subscriptions").assertExists()
        shot("02_preview")

        show(ImportUiState(stage = ImportStage.EDIT, files = listOf(good), editingFile = 1))
        rule.onNodeWithText("Use these columns").assertExists()
        shot("03_adjust_columns")

        val tx = good.analysis.transactions
        val existing = listOf(ExistingSubscription("Streamflix HD"))
        val groups = Suggestions.group(RecurringDetector.detect(tx, LocalDate.of(2026, 7, 1), "EUR"))
        val all = groups.likely + groups.bills + groups.low
        val dups = all.filter { Suggestions.isDuplicate(it, existing) }.map { it.key }.toSet()
        val review = ReviewState(groups, dups, all.filter { Suggestions.preselected(it, existing) }.map { it.key }.toSet(), Coverage.of(tx.map { it.date }, tx.size), 258, 0)
        show(ImportUiState(stage = ImportStage.REVIEW, review = review))
        rule.onNodeWithText("Likely subscriptions").assertExists()
        shot("04_review")

        // one month only -> unconfirmed suggestions + guidance
        val oneMonth = tx.filter { it.date.year == 2025 && it.date.monthValue == 3 } + tx.filter { it.description.contains("NETFLIX", true) }.take(1)
        val g1 = Suggestions.group(RecurringDetector.detect(oneMonth, LocalDate.of(2025, 4, 1), "EUR"))
        val a1 = g1.likely + g1.bills + g1.low
        show(ImportUiState(stage = ImportStage.REVIEW, review = ReviewState(g1, emptySet(), a1.filter { Suggestions.preselected(it, emptyList()) }.map { it.key }.toSet(), Coverage.of(oneMonth.map { it.date }, oneMonth.size), 30, 0)))
        shot("05_review_one_month")

        show(ImportUiState(stage = ImportStage.REVIEW, review = review), dark = true)
        shot("06_review_dark")
    }
}
