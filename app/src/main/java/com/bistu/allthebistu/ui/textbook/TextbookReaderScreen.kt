package com.bistu.allthebistu.ui.textbook

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.textbook.Textbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File

/** 阅读器底色：比纯黑浅一点，长时间看不那么"闷" */
private val ReaderBackground = Color(0xFF23232A)

/** 宽屏阈值：≥600dp 视为平板/横屏，双页并排 */
private val TwoPageMinWidth = 600.dp

/**
 * PDF 教材阅读器。
 *
 * 设计取舍（都是踩过的坑）：
 *  - **适应宽度 + 连续滚动**，不做双指缩放：教材是 A4 版面，整页塞进手机屏字太小，
 *    按宽度铺满、上下连续滚才是真正能读的形态；缩放留到以后有需求再加。
 *  - **平板双页**：宽 ≥ [TwoPageMinWidth] 时像真书一样左右两页，横向空间不浪费。
 *  - **顶栏不压内容**：以前做成浮层会盖住第一页的书眉，现在是上下结构，
 *    页码栏固定在上面，页面从它下面开始。
 *  - **进度记忆**：滚动停手 800ms 落一次，避免频繁写盘。
 *  - **不反色**：深色模式下 PDF 仍保持原色，强行反色会让插图、图表、公式全部失真。
 */
@Composable
fun TextbookReaderScreen(
    textbook: Textbook,
    file: File,
    onBack: () -> Unit,
    onProgress: (page: Int) -> Unit
) {
    BackHandler(onBack = onBack)

    // 打开失败（文件被删、PDF 损坏）时给明确反馈，不要白屏
    val book = remember(file) { runCatching { PdfBook(file) }.getOrNull() }
    DisposableEffect(book) {
        onDispose { book?.close() }
    }

    var jumpOpen by remember { mutableStateOf(false) }
    // 对话框里确认的页码；真正的滚动放在 listState 所在的作用域里做
    var jumpTarget by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ReaderBackground)
    ) {
        if (book == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("这本教材打不开了", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "文件可能已被删除或损坏，可以回教材库重新导入。",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onBack) { Text("返回", color = Color.White) }
            }
            return@Box
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val perItem = if (maxWidth >= TwoPageMinWidth) 2 else 1
            val gap = if (perItem == 2) 8.dp else 0.dp
            val horizontalPadding = if (perItem == 2) 20.dp else 0.dp
            val pageWidth = (maxWidth - horizontalPadding * 2 - gap * (perItem - 1)) / perItem
            val itemCount = (book.pageCount + perItem - 1) / perItem

            val listState = rememberLazyListState(
                // 接着上次读的位置打开（lastPage 是 0-based，双页模式下除以 2 换算成"第几屏"）
                initialFirstVisibleItemIndex = (textbook.lastPage / perItem)
                    .coerceIn(0, (itemCount - 1).coerceAtLeast(0))
            )

            // 跳页：页码 → 第几屏（双页模式下 2 页一屏）
            LaunchedEffect(jumpTarget, perItem) {
                val target = jumpTarget ?: return@LaunchedEffect
                listState.scrollToItem(
                    ((target - 1) / perItem).coerceIn(0, (itemCount - 1).coerceAtLeast(0))
                )
                jumpTarget = null
            }

            // 滚动停手 800ms 再落盘；这里只把"当前页"报给上层，写库在上层做
            LaunchedEffect(listState, perItem) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .collectLatest { idx ->
                        delay(800)
                        onProgress((idx * perItem).coerceAtMost(book.pageCount - 1))
                    }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // ── 顶栏：书名 + 页码（点页码跳页）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = textbook.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    val currentPage = (listState.firstVisibleItemIndex * perItem + 1)
                        .coerceIn(1, book.pageCount)
                    Text(
                        text = "$currentPage / ${book.pageCount}",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { jumpOpen = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(count = itemCount, key = { it }) { itemIndex ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(gap)
                        ) {
                            for (offset in 0 until perItem) {
                                val page = itemIndex * perItem + offset
                                if (page < book.pageCount) {
                                    PdfPageImage(
                                        book = book,
                                        pageIndex = page,
                                        width = pageWidth
                                    )
                                } else {
                                    // 单数页的最后一屏：右侧留空，保持左边那页不居中乱跑
                                    Spacer(Modifier.width(pageWidth))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (jumpOpen && book != null) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { jumpOpen = false },
            title = { Text("跳到第几页") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { s -> input = s.filter { it.isDigit() }.take(5) },
                    singleLine = true,
                    placeholder = { Text("1 ~ ${book.pageCount}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    input.toIntOrNull()?.let { target ->
                        jumpTarget = target.coerceIn(1, book.pageCount)
                    }
                    jumpOpen = false
                }) { Text("跳转") }
            },
            dismissButton = {
                TextButton(onClick = { jumpOpen = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 单页渲染。
 *
 * 先用 [PdfBook.ratioOf] 把占位高度撑出来（A4 约 1.414），再异步渲染位图，
 * 这样滚动时布局不会因为"图片加载完成"而跳动。
 */
@Composable
private fun PdfPageImage(
    book: PdfBook,
    pageIndex: Int,
    width: Dp
) {
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    val ratio = remember(pageIndex) { book.ratioOf(pageIndex) }

    var bitmap by remember(pageIndex, widthPx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageIndex, widthPx) {
        // 渲染是 CPU 密集 + 内部串行加锁，放到 Default 线程，别占主线程
        bitmap = withContext(Dispatchers.Default) { book.render(pageIndex, widthPx) }
    }

    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(1f / ratio)
            .background(Color.White)
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "第 ${pageIndex + 1} 页",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
