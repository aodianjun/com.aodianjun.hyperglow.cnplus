package com.eza.hyperglow.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 帮助内容来源:仓库根 FAQ.md 经构建任务 copyFaqAsset 同步进 assets,仓库内不维护第二份副本。 */
private const val HELP_ASSET_PATH = "FAQ.md"

/** 关于页四个内容子页(帮助/更新日志/贡献者/开源许可)共用的页面骨架。 */
@Composable
private fun AboutContentScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = appSurfaceColor(),
        topBar = { AppTopBar(title = title, onBack = onBack) }
    ) { innerPadding ->
        content(innerPadding)
    }
}

private fun contentListPadding(innerPadding: PaddingValues): PaddingValues = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = innerPadding.calculateTopPadding() + 12.dp,
    bottom = innerPadding.calculateBottomPadding() + 24.dp
)

@Composable
private fun FullScreenStatus(
    text: String,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    innerPadding: PaddingValues = PaddingValues(0.dp)
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding() + 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        if (retryLabel != null && onRetry != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(text = retryLabel, onClick = onRetry)
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 48.dp)
    )
}

@Composable
internal fun HelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableStateOf(0) }
    var lines by remember { mutableStateOf<List<MarkdownLine>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        failed = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(HELP_ASSET_PATH).bufferedReader().use { reader -> reader.readText() }
            }.mapCatching { parseMarkdownLines(it) }
        }
        result.onSuccess { lines = it }
        failed = result.isFailure
    }
    AboutContentScaffold(title = stringResource(R.string.title_help), onBack = onBack) { innerPadding ->
        val current = lines
        when {
            current != null -> MarkdownContent(current, innerPadding)
            failed -> FullScreenStatus(
                text = stringResource(R.string.help_load_failed),
                retryLabel = stringResource(R.string.action_retry),
                onRetry = { attempt++ },
                innerPadding = innerPadding
            )
            else -> FullScreenStatus(
                text = stringResource(R.string.about_content_loading),
                innerPadding = innerPadding
            )
        }
    }
}

@Composable
private fun MarkdownContent(lines: List<MarkdownLine>, innerPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentListPadding(innerPadding)
    ) {
        items(lines) { line -> MarkdownLineView(line) }
    }
}

@Composable
internal fun ChangelogScreen(onBack: () -> Unit) {
    var attempt by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<ReleaseEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        failed = false
        entries = null
        val list = withContext(Dispatchers.IO) { queryGitHubReleases() }
        if (list == null) failed = true else entries = list
    }
    AboutContentScaffold(title = stringResource(R.string.title_changelog), onBack = onBack) { innerPadding ->
        val current = entries
        when {
            current != null -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentListPadding(innerPadding)
            ) {
                if (current.isEmpty()) {
                    item { StatusLine(stringResource(R.string.changelog_empty)) }
                }
                items(current, key = { it.tagName }) { entry -> ReleaseCard(entry) }
            }
            failed -> FullScreenStatus(
                text = stringResource(R.string.about_content_load_failed),
                retryLabel = stringResource(R.string.action_retry),
                onRetry = { attempt++ },
                innerPadding = innerPadding
            )
            else -> FullScreenStatus(
                text = stringResource(R.string.about_content_loading),
                innerPadding = innerPadding
            )
        }
    }
}

@Composable
private fun ReleaseCard(entry: ReleaseEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.tagName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            entry.publishedDate?.let { date ->
                Text(
                    date,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        if (entry.title.isNotEmpty() && entry.title != entry.tagName) {
            Text(
                entry.title,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (entry.body.isNotBlank()) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                parseMarkdownLines(entry.body).forEach { line -> MarkdownLineView(line) }
            }
        }
    }
}

@Composable
internal fun ContributorsScreen(onBack: () -> Unit) {
    var attempt by remember { mutableStateOf(0) }
    var contributors by remember { mutableStateOf<List<ContributorEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        failed = false
        contributors = null
        val list = withContext(Dispatchers.IO) { queryGitHubContributors() }
        if (list == null) failed = true else contributors = list
    }
    AboutContentScaffold(title = stringResource(R.string.title_contributors), onBack = onBack) { innerPadding ->
        val current = contributors
        when {
            current != null -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentListPadding(innerPadding)
            ) {
                if (current.isEmpty()) {
                    item { StatusLine(stringResource(R.string.contributors_empty)) }
                }
                items(current, key = { it.login }) { entry -> ContributorRow(entry) }
            }
            failed -> FullScreenStatus(
                text = stringResource(R.string.about_content_load_failed),
                retryLabel = stringResource(R.string.action_retry),
                onRetry = { attempt++ },
                innerPadding = innerPadding
            )
            else -> FullScreenStatus(
                text = stringResource(R.string.about_content_loading),
                innerPadding = innerPadding
            )
        }
    }
}

@Composable
private fun ContributorRow(entry: ContributorEntry) {
    val context = LocalContext.current
    var avatar by remember(entry.login) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(entry.login, entry.avatarUrl) {
        avatar = withContext(Dispatchers.IO) { fetchImageBitmap(entry.avatarUrl) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (entry.profileUrl.isEmpty()) Modifier
                else Modifier.clickable { openExternalUrl(context, entry.profileUrl) }
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = avatar
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    entry.login.take(1).uppercase(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(entry.login, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.contributors_contributions, entry.contributions),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
internal fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    AboutContentScaffold(title = stringResource(R.string.title_licenses), onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentListPadding(innerPadding)
        ) {
            item {
                Text(
                    stringResource(R.string.licenses_summary),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                SettingsCard {
                    LICENSE_ENTRIES.forEach { entry ->
                        ArrowPreference(
                            title = entry.name,
                            summary = entry.license,
                            onClick = { openExternalUrl(context, entry.url) }
                        )
                    }
                }
            }
        }
    }
}

private data class LicenseEntry(val name: String, val license: String, val url: String)

private val LICENSE_ENTRIES = listOf(
    LicenseEntry("HyperGlow", "GPL-3.0", "https://github.com/amarinne/hyperglow"),
    LicenseEntry("HyperLyric", "GPL-3.0", "https://github.com/limczhh/HyperLyric"),
    LicenseEntry("Miuix (Compose UI framework)", "Apache-2.0", "https://github.com/compose-miuix-ui/miuix"),
    LicenseEntry("DexKit", "Apache-2.0", "https://github.com/LuckyPray/DexKit"),
    LicenseEntry("SuperLyricApi", "LGPL-2.1", "https://github.com/HChenX/SuperLyricApi"),
    LicenseEntry("Lyricon subscriber SDK", "Apache-2.0", "https://github.com/proify/Lyricon"),
    LicenseEntry("kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    LicenseEntry("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    LicenseEntry("Noto Sans / Noto Sans SC fonts", "SIL OFL 1.1", "https://fonts.google.com/noto")
)

@Composable
private fun MarkdownLineView(line: MarkdownLine) {
    when (line.kind) {
        MarkdownLineKind.HEADING -> Text(
            text = annotatedSpans(line.spans),
            fontSize = when (line.level) {
                1 -> 20.sp
                2 -> 18.sp
                else -> 16.sp
            },
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                top = if (line.level == 1) 14.dp else 10.dp,
                bottom = 6.dp
            )
        )
        MarkdownLineKind.BULLET -> PrefixedMarkdownRow("•", line)
        MarkdownLineKind.ORDERED -> PrefixedMarkdownRow(line.orderedPrefix.orEmpty(), line)
        MarkdownLineKind.CODE -> Text(
            text = AnnotatedString(line.spans.joinToString(separator = "") { span -> span.text }),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
        )
        MarkdownLineKind.PARAGRAPH -> Text(
            text = annotatedSpans(line.spans),
            fontSize = 15.sp,
            modifier = Modifier.padding(vertical = 3.dp)
        )
    }
}

@Composable
private fun PrefixedMarkdownRow(prefix: String, line: MarkdownLine) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            prefix,
            fontSize = 15.sp,
            modifier = Modifier.width(18.dp)
        )
        Text(text = annotatedSpans(line.spans), fontSize = 15.sp)
    }
}

private fun annotatedSpans(spans: List<MarkdownSpan>): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        if (span.bold || span.code) {
            withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null
                )
            ) { append(span.text) }
        } else {
            append(span.text)
        }
    }
}
