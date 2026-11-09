// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.coffer.R
import app.coffer.ui.components.CofferIcons
import app.coffer.ui.components.HexBadge
import app.coffer.ui.components.rememberAnimationsEnabled
import app.coffer.ui.theme.CofferTheme
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector, val title: Int, val body: Int)

private val pages = listOf(
    Page(Icons.Filled.Lock, R.string.onb1_title, R.string.onb1_body),
    Page(CofferIcons.VisibilityOff, R.string.onb2_title, R.string.onb2_body),
    Page(CofferIcons.FolderOpen, R.string.onb3_title, R.string.onb3_body),
)

/** Three pages on first launch; every exit marks the onboarding as done. */
@Composable
fun OnboardingScreen(onFinish: (OnboardingExit) -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val animate = rememberAnimationsEnabled()
    val last = pager.currentPage == pages.lastIndex
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = { onFinish(OnboardingExit.Skip) }) { Text(stringResource(R.string.onb_skip)) }
                else Spacer(Modifier.height(48.dp))
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                val p = pages[i]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    HexBadge(
                        p.icon,
                        size = 128.dp,
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.height(32.dp))
                    Text(stringResource(p.title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(p.body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            PageDots(pager.currentPage, pages.size, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
            Column(Modifier.fillMaxWidth().padding(24.dp, 8.dp, 24.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val big = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                if (last) {
                    Button(
                        onClick = { onFinish(OnboardingExit.AddExisting) },
                        modifier = big,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) { Text(stringResource(R.string.onb_add)) }
                    OutlinedButton(onClick = { onFinish(OnboardingExit.CreateNew) }, modifier = big) {
                        Text(stringResource(R.string.onb_create))
                    }
                } else {
                    Button(onClick = {
                        scope.launch {
                            if (animate) pager.animateScrollToPage(pager.currentPage + 1) else pager.scrollToPage(pager.currentPage + 1)
                        }
                    }, modifier = big) { Text(stringResource(R.string.onb_next)) }
                }
            }
        }
    }
}

@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.onb_page, current + 1, count)
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 24.dp else 8.dp, label = "dot")
            val color by animateColorAsState(
                if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                label = "dotColor",
            )
            Box(Modifier.height(8.dp).width(width).background(color, CircleShape))
        }
    }
}

@Preview @Composable private fun OnboardingLight() = CofferTheme(dark = false) { OnboardingScreen {} }
@Preview @Composable private fun OnboardingDark() = CofferTheme(dark = true) { OnboardingScreen {} }
