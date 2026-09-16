package com.os4.musiccover.ui.screen.features

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ModuleBridge
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.PageScaffold
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/**
 * The notification shade's settings: the album cover as a moving background behind the shade.
 *
 * Its own screen rather than a section of the lock screen's, because it is a separate feature
 * with a separate failure surface. See [ShadeActivity][com.os4.musiccover.ShadeActivity] for why
 * it is a screen and not a page.
 *
 * The frame is [PageScaffold]'s. Every value is an Int on the wire and the module clamps it.
 */
@Composable
internal fun ShadePageView(
    isBlurEnabled: Boolean,
    refreshKey: Int,
    extraBottomPadding: Dp = 0.dp,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var module by remember { mutableStateOf(ModuleBridge.State()) }
    LaunchedEffect(refreshKey) { module = ModuleBridge.query(context) }
    val master = (module.shade["enabled"] ?: 1) != 0
    // Everything below the master switch greys out with it; the switch itself only needs the module.
    val enabled = module.alive && master

    // Local first, then the module: its answer arrives a broadcast later.
    val push: (String, Int) -> Unit = { key, value ->
        module = module.copy(shade = module.shade + (key to value))
        ModuleBridge.setShade(context, key, value)
    }

    PageScaffold(
        title = stringResource(R.string.features_shade_title),
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        onBack = onBack,
    ) {
        item {
            Column {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.shade_enabled),
                        checked = master,
                        enabled = module.alive,
                        onCheckedChange = { push("enabled", if (it) 1 else 0) },
                    )
                }

                SmallTitle(text = stringResource(R.string.shade_section_behaviour))
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                ) {
                    // Index is the module's mode: 0 hands the shade back, 1 keeps the last cover.
                    val modes = listOf(
                        stringResource(R.string.shade_mode_restore),
                        stringResource(R.string.shade_mode_keep),
                    )
                    WindowDropdownPreference(
                        title = stringResource(R.string.shade_mode),
                        items = modes,
                        selectedIndex = (module.shade["mode"] ?: 0).coerceIn(0, modes.lastIndex),
                        enabled = enabled,
                        onSelectedIndexChange = { push("mode", it) },
                    )
                }
            }
        }
    }
}
