package org.libepub.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libepub.core.css.WritingMode
import org.libepub.core.toc.TocEntry
import org.libepub.render.widget.EpubReaderContent
import org.libepub.render.widget.EpubReaderOverlays
import org.libepub.render.widget.EpubReaderState
import org.libepub.render.widget.ReaderMode

/**
 * Reader screen: opens the book on a background dispatcher, wires the render
 * layer to the app state machine and adds the reader chrome (TOC, settings,
 * progress). Footnotes, galleries and zoom come for free from the render overlays.
 */
@Composable
fun ReaderScreen(
    entry: BookEntry,
    repository: BookSource,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val progress = remember { SharedPreferencesProgressStore(context.applicationContext) }
    var loaded by remember(entry) { mutableStateOf<LoadedBook?>(null) }
    var error by remember(entry) { mutableStateOf<String?>(null) }

    LaunchedEffect(entry) {
        val result = withContext(Dispatchers.IO) {
            runCatching { openBook(entry, repository, context.applicationContext, progress) }
        }
        result.onSuccess { loaded = it }
        result.onFailure { error = it.message ?: it::class.java.simpleName }
    }

    val current = loaded
    DisposableEffect(current) {
        onDispose { current?.session?.close() }
    }

    when {
        error != null -> Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("打开失败：$error", color = Color(0xFFB00020))
                TextButton(onClick = onBack) { Text("返回书架") }
            }
        }
        current == null -> Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            contentAlignment = Alignment.Center,
        ) {
            Text("正在打开…")
        }
        else -> ReaderContent(current, onBack)
    }
}

@Composable
private fun ReaderContent(book: LoadedBook, onBack: () -> Unit) {
    val reader = book.reader
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // System back / predictive back: close the topmost overlay first, then the
    // reader. Without this the zoom viewer could only be dismissed by its
    // on-screen button.
    BackHandler(enabled = true) {
        when {
            reader.zoomedImage != null -> reader.closeZoom()
            reader.gallery != null -> reader.closeGallery()
            reader.footnote != null -> reader.closeFootnote()
            showSettings -> showSettings = false
            showToc -> showToc = false
            else -> onBack()
        }
    }

    LaunchedEffect(reader.spineIndex, reader.pageIndex) {
        reader.locator()?.let { book.app.save(it) }
    }

    // Keep status/navigation bar icons readable on paper, sepia and night themes.
    val view = LocalView.current
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(reader.themeKey) {
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            val lightIcons = reader.themeKey != "night"
            isAppearanceLightStatusBars = lightIcons
            isAppearanceLightNavigationBars = lightIcons
        }
    }

    // The reading surface is full-bleed (immersive); the chrome floats above it
    // and the reader reserves exactly the chrome + system-bar height, so text is
    // never covered. Paged mode shortens the page box by the reserve; scroll
    // mode appends it as blank tail space.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeLeft = WindowInsets.safeDrawing.getLeft(density, layoutDirection)
    val safeTop = WindowInsets.safeDrawing.getTop(density)
    val safeRight = WindowInsets.safeDrawing.getRight(density, layoutDirection)
    val safeBottom = WindowInsets.safeDrawing.getBottom(density)
    val sidePaddingPx = with(density) { 12.dp.toPx() }
    val topBarHeightPx = with(density) { 48.dp.toPx() }
    val bottomBarHeightPx = with(density) { 24.dp.toPx() }
    SideEffect {
        reader.contentInsetLeftPx = safeLeft + sidePaddingPx
        reader.contentInsetTopPx = safeTop + topBarHeightPx
        reader.contentInsetRightPx = safeRight + sidePaddingPx
        reader.contentInsetBottomPx = safeBottom + bottomBarHeightPx
    }

    Box(Modifier.fillMaxSize().background(reader.theme.background)) {
        EpubReaderContent(reader)

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(48.dp)
                .background(reader.theme.background.copy(alpha = 0.88f))
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("返回") }
            Row {
                TextButton(onClick = { showToc = true }, modifier = Modifier.testTag("toc")) { Text("目录") }
                TextButton(onClick = { showSettings = true }, modifier = Modifier.testTag("settings")) { Text("设置") }
            }
        }

        Text(
            text = "${reader.pageIndex + 1}/${reader.pageCount()} · ${(reader.progress() * 100).toInt()}%",
            fontSize = 11.sp,
            color = Color(0x998A6D3B),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 4.dp)
                .background(reader.theme.background.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )

        // Full-bleed overlays cover the chrome as well.
        EpubReaderOverlays(reader)

        if (showToc) {
            TocOverlay(book, onDismiss = { showToc = false })
        }
        if (showSettings) {
            SettingsOverlay(reader, onDismiss = { showSettings = false })
        }
    }
}

@Composable
private fun TocOverlay(book: LoadedBook, onDismiss: () -> Unit) {
    OverlayCard(title = "目录", onClose = onDismiss, modifier = Modifier.testTag("toc-overlay")) {
        val entries = book.app.flatToc
        if (entries.isEmpty()) {
            Text("本书没有目录", color = Color(0xFF8A6D3B), modifier = Modifier.padding(8.dp))
            return@OverlayCard
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(entries) { entry ->
                TocRow(entry) {
                    if (book.app.selectToc(entry)) {
                        book.reader.goToHref(entry.href!!, entry.fragment)
                    }
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun TocRow(entry: TocEntry, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (entry.children.isNotEmpty()) {
                Text("▾ ", fontSize = 12.sp, color = Color(0xFF8A6D3B))
            }
            Text(
                text = entry.title.ifBlank { "（无标题）" },
                fontSize = 15.sp,
                color = Color(0xFF3B2F1E),
                fontWeight = if (entry.children.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun SettingsOverlay(reader: EpubReaderState, onDismiss: () -> Unit) {
    OverlayCard(title = "阅读设置", onClose = onDismiss, modifier = Modifier.testTag("settings-overlay")) {
        SettingRow("字号") {
            TextButton(onClick = { reader.setFontSize(reader.fontSizeSp - 1f) }) { Text("A-") }
            Text("${reader.fontSizeSp.toInt()}", color = Color(0xFF3B2F1E))
            TextButton(onClick = { reader.setFontSize(reader.fontSizeSp + 1f) }) { Text("A+") }
        }
        SettingRow("阅读模式") {
            TextButton(onClick = {
                reader.mode = if (reader.mode == ReaderMode.PAGED) ReaderMode.SCROLL else ReaderMode.PAGED
            }) {
                Text(if (reader.mode == ReaderMode.PAGED) "翻页（点击切换为滚动）" else "滚动（点击切换为翻页）")
            }
        }
        SettingRow("主题") {
            TextButton(onClick = { reader.setTheme(nextTheme(reader.themeKey)) }) {
                Text(themeLabel(reader.themeKey))
            }
        }
        SettingRow("排版方向") {
            TextButton(onClick = { reader.toggleWritingMode() }) {
                Text(writingLabel(reader.writingMode))
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFF8A6D3B), modifier = Modifier.padding(end = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

private fun nextTheme(current: String): String = when (current) {
    "paper" -> "sepia"
    "sepia" -> "night"
    else -> "paper"
}

private fun themeLabel(key: String): String = when (key) {
    "sepia" -> "复古"
    "night" -> "夜间"
    else -> "纸张"
}

private fun writingLabel(mode: WritingMode): String = when (mode) {
    WritingMode.HORIZONTAL_TB -> "横排"
    WritingMode.VERTICAL_RL -> "竖排（右起）"
    WritingMode.VERTICAL_LR -> "竖排（左起）"
}
