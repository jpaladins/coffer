// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.viewer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coffer.R
import app.coffer.ui.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen(viewModel: ViewerViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ViewerContentView(state, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerContentView(state: ViewerUiState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val c = state.content) {
                ViewerContent.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ViewerContent.Image -> DecodedImage(c.bytes)
                is ViewerContent.Text -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (c.truncated) Text(stringResource(R.string.viewer_truncated), color = MaterialTheme.colorScheme.secondary)
                    SelectionContainer { Text(c.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
                ViewerContent.TooLarge -> Message(stringResource(R.string.viewer_too_large))
                ViewerContent.Unsupported -> Message(stringResource(R.string.viewer_unsupported))
                is ViewerContent.Failed -> Message(c.error.userMessage())
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text) }
}

/** Decodes in memory, downsampled to the screen so large photos cannot exhaust the heap. */
@Composable
private fun DecodedImage(bytes: ByteArray) {
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val maxPx = with(density) { maxOf(cfg.screenWidthDp, cfg.screenHeightDp).dp.roundToPx() } * 2
    // Unit = still decoding, null = undecodable.
    val bitmap by produceState<Any?>(Unit, bytes) {
        value = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        }
    }
    when (val b = bitmap) {
        Unit -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        is ImageBitmap -> Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else -> Message(stringResource(R.string.viewer_unsupported))
    }
}
