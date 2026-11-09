// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.coffer.data.Entry

/** Loads a preview for a visible entry; null while loading or when there is none. */
typealias ThumbSource = suspend (entry: Entry, targetPx: Int) -> ImageBitmap?

/** Photo preview fading in over the file icon; the icon stays when there is no preview. */
@Composable
fun Thumb(e: Entry, source: ThumbSource?, targetPx: Int, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    if (source == null || e.isDir || !isThumbnailable(e.name) || e.size > THUMB_MAX_FILE) {
        fallback()
        return
    }
    val bmp by produceState<ImageBitmap?>(null, e.name, e.size, e.modified) { value = source(e, targetPx) }
    Crossfade(bmp, label = "thumb") { b ->
        if (b == null) fallback()
        else Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.fillMaxSize())
    }
}
