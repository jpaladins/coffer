// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.about

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import app.coffer.ui.components.CofferTopBar
import app.coffer.ui.theme.CofferRadii
import app.coffer.ui.theme.TabularNums

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.coffer.BuildConfig
import app.coffer.R
import app.coffer.ui.CofferMark

/** Version, license, trademark and privacy notes, and the third-party licenses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    val licenses = remember(showLicenses) {
        if (showLicenses) runCatching { context.assets.open("third_party.md").bufferedReader().readText() }.getOrDefault("") else ""
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { CofferTopBar(stringResource(R.string.about_title), scroll, onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InfoCard(Alignment.CenterHorizontally) {
                CofferMark(size = 72.dp)
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            InfoCard {
                Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.about_trademark),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InfoCard(Alignment.CenterHorizontally) { DeveloperCredit() }
            InfoCard {
                TextButton(onClick = { showLicenses = !showLicenses }) { Text(stringResource(R.string.about_licenses)) }
                if (showLicenses) {
                    Text(licenses, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(align: Alignment.Horizontal = Alignment.Start, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(CofferRadii.card)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = align,
            content = content,
        )
    }
}

private class SocialLink(@DrawableRes val icon: Int, @StringRes val label: Int, val url: String)

private val socialLinks = listOf(
    SocialLink(R.drawable.ic_social_github, R.string.about_social_github, "https://github.com/jpaladins"),
    SocialLink(R.drawable.ic_social_instagram, R.string.about_social_instagram, "https://www.instagram.com/jpaladins"),
    SocialLink(R.drawable.ic_social_threads, R.string.about_social_threads, "https://www.threads.com/@jpaladins"),
)

/** "Developed by" with the JPaladins logo and social channels; links open in the user's browser. */
@Composable
private fun DeveloperCredit() {
    val uriHandler = LocalUriHandler.current
    Text(stringResource(R.string.about_developed_by), style = MaterialTheme.typography.labelLarge)
    Image(
        painter = painterResource(R.drawable.jpaladins_logo),
        contentDescription = stringResource(R.string.about_developer),
        modifier = Modifier.height(40.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        socialLinks.forEach { link ->
            FilledIconButton(
                // No browser installed: nothing to open, stay on the screen.
                onClick = { runCatching { uriHandler.openUri(link.url) } },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colorResource(R.color.jpaladins_chip),
                    contentColor = colorResource(R.color.jpaladins_lime),
                ),
            ) {
                Icon(
                    painterResource(link.icon),
                    contentDescription = stringResource(link.label),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
