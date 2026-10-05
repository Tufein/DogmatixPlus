package com.cortinadev.dogmatix.ui.screens.contact

import com.cortinadev.dogmatix.ui.theme.accentInk
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.NavChevron
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.PanelTone
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.ScreenTitle
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource

private data class CreditLink(val iconRes: Int, val labelRes: Int, val url: String, val display: String)

private val plusLinks = listOf(
    CreditLink(R.drawable.ic_github, R.string.credits_link_github, "https://github.com/Tufein/DogmatixPlus", "github.com/Tufein/DogmatixPlus"),
)

private val forkLinks = listOf(
    CreditLink(R.drawable.ic_github, R.string.credits_link_github, "https://github.com/cortinadev", "github.com/cortinadev"),
    CreditLink(R.drawable.ic_linkedin, R.string.credits_link_linkedin, "https://www.linkedin.com/in/rafa-cortina", "linkedin.com/in/rafa-cortina"),
    CreditLink(R.drawable.ic_web, R.string.credits_link_web, "https://cortina.dev", "cortina.dev"),
)

private val originalLinks = listOf(
    CreditLink(R.drawable.ic_github, R.string.credits_link_github, "https://github.com/santiifm", "github.com/santiifm"),
    CreditLink(R.drawable.ic_linkedin, R.string.credits_link_linkedin, "https://www.linkedin.com/in/santiifm", "linkedin.com/in/santiifm"),
)

@Composable
fun ContactScreen(navController: NavController) {
    val isLandscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    // No background of its own: the app's glow shows through, the cards sit on it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle(
            text = stringResource(R.string.credits_title),
            subtitle = stringResource(R.string.about_v5_subtitle),
            icon = R.drawable.ic_heart,
            modifier = Modifier.widthIn(max = 860.dp)
        )
        // This build first, then the projects it is built on: DogmatixPlus -> Dogmatix -> Milou.
        PlusCard(Modifier.fillMaxWidth().widthIn(max = 860.dp))
        if (isLandscape) {
            Row(
                modifier = Modifier.widthIn(max = 860.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                ForkCard(Modifier.weight(1f))
                OriginalCard(Modifier.weight(1f))
            }
        } else {
            ForkCard(Modifier.fillMaxWidth())
            OriginalCard(Modifier.fillMaxWidth())
        }
    }
}

/** The hero card: the mascot, this build's name and version, and the line it descends from. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlusCard(modifier: Modifier = Modifier) {
    Panel(modifier = modifier, tone = PanelTone.Accent, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(
                painterResource(R.drawable.milou),
                contentDescription = null,
                modifier = Modifier.size(72.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.credits_plus_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Pill("v" + BuildConfig.VERSION_NAME, tone = PillTone.Strong)
                }
                Text(
                    stringResource(R.string.credits_plus_role),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.credits_plus_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        // DogmatixPlus › Dogmatix › Milou
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Pill(stringResource(R.string.credits_plus_name), tone = PillTone.Accent, icon = R.drawable.ic_sparkle)
            NavChevron(Modifier.align(Alignment.CenterVertically))
            Pill(stringResource(R.string.about_v5_lineage_dogmatix), tone = PillTone.Neutral)
            NavChevron(Modifier.align(Alignment.CenterVertically))
            Pill(stringResource(R.string.about_v5_lineage_milou), tone = PillTone.Neutral)
        }
        Spacer(Modifier.height(8.dp))
        plusLinks.forEach { LinkRow(it) }
    }
}

@Composable
private fun ForkCard(modifier: Modifier = Modifier) = CreditCard(
    name = stringResource(R.string.credits_fork_name),
    role = stringResource(R.string.credits_fork_role),
    icon = R.drawable.ic_palette,
    links = forkLinks,
    modifier = modifier
)

@Composable
private fun OriginalCard(modifier: Modifier = Modifier) = CreditCard(
    name = stringResource(R.string.credits_original_name),
    role = stringResource(R.string.credits_original_role),
    note = stringResource(R.string.credits_original_note),
    icon = R.drawable.ic_build,
    links = originalLinks,
    modifier = modifier
)

@Composable
private fun CreditCard(
    name: String,
    role: String,
    icon: Int,
    links: List<CreditLink>,
    modifier: Modifier = Modifier,
    note: String? = null
) {
    Panel(modifier = modifier, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, size = 38.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = role,
                    style = MaterialTheme.typography.bodyMedium,
                    color = accentInk(),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        links.forEach { LinkRow(it) }
    }
}

/** A link with its service's mark; opens in the browser. */
@Composable
private fun LinkRow(link: CreditLink) {
    val uriHandler = LocalUriHandler.current
    val source = rememberFocusSource()
    val label = stringResource(link.labelRes)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .focusRing(source, cornerRadius = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = source, indication = null) { uriHandler.openUri(link.url) }
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(
            painter = painterResource(link.iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = link.display,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(R.drawable.ic_open_in_new),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
