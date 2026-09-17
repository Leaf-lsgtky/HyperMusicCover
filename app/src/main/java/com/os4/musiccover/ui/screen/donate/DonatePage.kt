package com.os4.musiccover.ui.screen.donate

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.PageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * The payment codes, as a screen of its own.
 *
 * One code is on screen at a time rather than both stacked: each image is a full poster, and two
 * of them one under the other is a page nobody reaches the bottom of. The tab row is the same
 * control the shade page already switches its groups with.
 *
 * "Save to album" is not decoration. The phone showing this page is usually the phone that would
 * have to scan the code, and no camera can scan its own screen - the way through is the album,
 * which both apps can pick a code out of.
 */
private data class DonateChannel(
    val name: String,
    val image: Int,
    val fileName: String,
    val mimeType: String,
)

@Composable
fun DonatePageContent(
    onBack: () -> Unit,
    isBlurEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val wechat = stringResource(R.string.donate_wechat)
    val alipay = stringResource(R.string.donate_alipay)
    val channels = remember(wechat, alipay) {
        listOf(
            DonateChannel(wechat, R.drawable.donate_wechat, "HyperMusicCover-WeChat.png", "image/png"),
            DonateChannel(alipay, R.drawable.donate_alipay, "HyperMusicCover-Alipay.jpg", "image/jpeg"),
        )
    }
    // Alipay, not the first tab: it is the one the author is paid through most, and a tab row
    // whose selection has to be moved before the page is useful is a tab row pointed the wrong
    // way. The order stays as it is - the tabs are read left to right whichever is selected.
    var selected by remember { mutableIntStateOf(channels.indexOfFirst { it.name == alipay }) }
    val channel = channels[selected]

    // null while nothing has been saved; true/false is the outcome of the last attempt, and it is
    // the button's own label that reports it. A Toast is not a channel this app has - they are
    // dropped whenever notifications are off, which is this app's default.
    var saveResult by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(saveResult, selected) {
        if (saveResult != null) {
            delay(2000)
            saveResult = null
        }
    }

    PageScaffold(
        title = stringResource(R.string.donate_title),
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    MiuixText(
                        text = stringResource(R.string.donate_thanks_title),
                        fontSize = 17.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    MiuixText(
                        text = stringResource(R.string.donate_thanks_summary),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item {
            TabRow(
                tabs = channels.map { it.name },
                selectedTabIndex = selected,
                onTabSelected = { selected = it },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp),
            )
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Crossfade rather than a straight swap: the two posters are entirely
                    // different colours, and cutting between a green one and a blue one reads as
                    // a flash where a fade reads as the same card turning over.
                    Crossfade(targetState = channel, label = "donateCode") { shown ->
                        Image(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .fillMaxWidth()
                                .squircleClip(cornerRadius = 16.dp),
                            painter = painterResource(shown.image),
                            contentDescription = shown.name,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MiuixText(
                            text = stringResource(R.string.donate_scan_hint),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(6.dp))
                        // The way out of the same-screen problem the line above describes, for
                        // the phones that have it: the assistant reads the code off the screen
                        // it is being shown on.
                        MiuixText(
                            text = stringResource(R.string.donate_xiaoai_tip),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = when (saveResult) {
                            true -> stringResource(R.string.donate_saved)
                            false -> stringResource(R.string.donate_save_failed)
                            null -> stringResource(R.string.donate_save)
                        },
                        onClick = {
                            scope.launch {
                                saveResult = withContext(Dispatchers.IO) {
                                    saveToAlbum(context, channel)
                                }
                            }
                        },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

/**
 * Copies the poster into the user's album.
 *
 * The bytes are copied rather than a Bitmap re-encoded: `openRawResource` on a drawable that is
 * already a PNG or a JPEG hands back the file itself, so what lands in the album is the image the
 * payment app was given, not a second-generation copy of it.
 *
 * No permission is asked for. Since Android 10 an app owns what it inserts into MediaStore, and
 * this app's minimum is well past that.
 */
private fun saveToAlbum(context: Context, channel: DonateChannel): Boolean = try {
    val resolver = context.contentResolver
    val pending = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, channel.fileName)
        put(MediaStore.Images.Media.MIME_TYPE, channel.mimeType)
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/HyperMusicCover",
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
    if (uri == null) {
        false
    } else {
        resolver.openOutputStream(uri).use { out ->
            if (out == null) throw IllegalStateException("no output stream")
            context.resources.openRawResource(channel.image).use { it.copyTo(out) }
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        true
    }
} catch (_: Throwable) {
    false
}
