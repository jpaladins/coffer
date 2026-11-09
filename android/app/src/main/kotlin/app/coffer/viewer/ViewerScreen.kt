// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.viewer

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coffer.R
import app.coffer.ui.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ViewerText = Color(0xFFE6E6E6)

@Composable
fun ViewerScreen(viewModel: ViewerViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ViewerContentView(state, onBack)
}

/** Full screen on black; one tap hides or shows the app and system bars. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ViewerContentView(state: ViewerUiState, onBack: () -> Unit) {
    var chrome by remember { mutableStateOf(true) }
    SystemBars(visible = chrome)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val c = state.content) {
            ViewerContent.Loading -> LoadingIndicator(Modifier.align(Alignment.Center))
            is ViewerContent.Image -> DecodedImage(c.bytes, state.name) { chrome = !chrome }
            is ViewerContent.Text -> Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(top = 64.dp).verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                if (c.truncated) Text(stringResource(R.string.viewer_truncated), color = MaterialTheme.colorScheme.tertiary)
                SelectionContainer {
                    Text(c.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = ViewerText)
                }
            }
            ViewerContent.TooLarge -> Message(stringResource(R.string.viewer_too_large))
            ViewerContent.Unsupported -> Message(stringResource(R.string.viewer_unsupported))
            is ViewerContent.Failed -> Message(c.error.userMessage())
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            TopAppBar(
                title = { Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        }
    }
}

/** Hides the status and navigation bars while the viewer chrome is hidden; restores them on exit. */
@Composable
private fun SystemBars(visible: Boolean) {
    val view = LocalView.current
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(visible) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, color = ViewerText) }
}

/**
 * Decodes in memory, downsampled to the screen so large photos cannot exhaust
 * the heap. Pinch to zoom and pan; double tap zooms 2.5× at the touched point.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DecodedImage(bytes: ByteArray, name: String, onTap: () -> Unit) {
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
        Unit -> Box(Modifier.fillMaxSize()) { LoadingIndicator(Modifier.align(Alignment.Center)) }
        is ImageBitmap -> ZoomableImage(b, stringResource(R.string.viewer_image, name), onTap)
        else -> Message(stringResource(R.string.viewer_unsupported))
    }
}

@Composable
private fun ZoomableImage(bitmap: ImageBitmap, description: String, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1) / 2
        val maxY = size.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    Image(
        bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { p ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = 2.5f
                            offset = clamp((center - p) * 1.5f, 2.5f)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val s = (scale * zoom).coerceIn(1f, 5f)
                    scale = s
                    offset = clamp(offset + pan, s)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
