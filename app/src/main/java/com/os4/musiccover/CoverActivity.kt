package com.os4.musiccover

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.os4.musiccover.ui.screen.features.CoverPageView
import com.os4.musiccover.ui.theme.AppTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * The lock screen cover's settings, as a screen of its own.
 *
 * A screen rather than a page swapped in place, which is the same choice [LicenseActivity] makes
 * and for the same reason: a whole screen arriving is the platform's own transition, it brings
 * its own back handling - gesture, button and the predictive-back animation on Android 13+ - and
 * the sub-page inside FeaturesPage that this replaces had to imitate all three and matched none
 * of them.
 *
 * Nothing is passed in. Like the licence list, this reads the app's own settings itself, so the
 * caller only has to name the class.
 */
class CoverActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val savedSettings = AppSettings.load(this)
        val themeMode = try {
            ColorSchemeMode.valueOf(savedSettings.themeMode)
        } catch (_: Exception) {
            ColorSchemeMode.System
        }
        val isBlurEnabled = savedSettings.isBlurEnabled

        setContent {
            AppTheme(themeMode = themeMode) {
                // refreshKey 0: it exists to re-ask the module when a tab is re-entered, and a
                // screen that is created fresh has nothing to re-ask.
                CoverPageView(
                    isBlurEnabled = isBlurEnabled,
                    refreshKey = 0,
                    onBack = { finish() },
                )
            }
        }
    }
}
