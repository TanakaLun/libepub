package org.libepub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * App root: bookshelf, then the reader for the chosen book. No navigation
 * library — one nullable selection is enough for a two-screen demo reader.
 */
@Composable
fun LibEpubApp(bookSource: BookSource? = null) {
    val context = LocalContext.current
    val repository = bookSource ?: remember { BookRepository(context.applicationContext) }
    val books = remember { repository.list() }
    var selected by remember { mutableStateOf<BookEntry?>(null) }

    // Bookshelf is a light screen; restore dark status/navigation icons when the
    // reader (which may be in night mode) leaves the composition.
    val view = LocalView.current
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(selected) {
        if (selected != null) return@LaunchedEffect
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    val book = selected
    if (book == null) {
        BookshelfScreen(books, onOpen = { selected = it })
    } else {
        ReaderScreen(book, repository, onBack = { selected = null })
    }
}

@Composable
fun BookshelfScreen(
    books: List<BookEntry>,
    onOpen: (BookEntry) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xFFF7F3E9))) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(20.dp),
        ) {
            Text(
                text = "书架",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF3B2F1E),
            )
            Text(
                text = "${books.size} 本书 · 纯原生 Compose 排版引擎",
                fontSize = 13.sp,
                color = Color(0xFF8A6D3B),
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            if (books.isEmpty()) {
                Text("assets/ 下没有 EPUB 文件", color = Color(0xFF8A6D3B))
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(books) { book ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0E2C8)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .testTag("book")
                            .clickable { onOpen(book) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = book.title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF3B2F1E),
                            )
                            Text(
                                text = book.fileName,
                                fontSize = 11.sp,
                                color = Color(0xFF8A6D3B),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Small overlay card used by the TOC and settings panels. */
@Composable
internal fun OverlayCard(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFF7F3E9))
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3B2F1E))
                TextButton(onClick = onClose) { Text("关闭") }
            }
            content()
        }
    }
}
