package app.subsonar.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.subsonar.ui.theme.SubsonarTheme
import java.util.Locale

/** AppCompatActivity (not plain ComponentActivity) so that [AppLanguages.set] also works on Android 8-12. */
class MainActivity : AppCompatActivity() {
    private val mainViewModel: MainViewModel by viewModels { AppViewModelFactory.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Number/date formatting follows the language the UI is actually shown in (system or per-app language).
        Locale.setDefault(resources.configuration.locales[0])
        enableEdgeToEdge()
        setContent {
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent // wait for DataStore to avoid a theme flash
            SubsonarTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                SubsonarNavHost()
            }
        }
    }
}
