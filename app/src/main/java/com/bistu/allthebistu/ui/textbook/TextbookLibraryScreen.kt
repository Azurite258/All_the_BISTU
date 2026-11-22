package com.bistu.allthebistu.ui.textbook

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.textbook.Textbook
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.pageBackgroundColor
import com.bistu.allthebistu.ui.theme.styleCard

/**
 * 教材库。
 *
 * 版式跟「AI 学习计划」二级页一致：顶栏 + 可滚动分组卡片。
 * 分组按课程来——教材和课表的对应关系就该是「这门课的书」，
 * 匹配不上的统一进"未分类"，而不是硬塞给某一门课。
 *
 * 导入走系统的多选文件选择器（`OpenMultipleDocuments`），
 * 用户一次选一堆 PDF，本页只负责收集 Uri 交给上层，真正的拷贝在上层做。
 */
@Composable
fun TextbookLibraryScreen(
    textbooks: List<Textbook>,
    /** 正在导入的文件名；空串表示当前没在导入 */
    importingName: String = "",
    /** 这一批要导入几本 / 已完成几本 */
    importDone: Int = 0,
    importTotal: Int = 0,
    onImport: (List<Uri>) -> Unit,
    onOpen: (Textbook) -> Unit,
    onDelete: (Textbook) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val metrics = LocalUiMetrics.current
    val colors = MaterialTheme.colorScheme

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onImport(uris) }

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackgroundColor())
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = colors.onBackground)
            }
            Text(
                text = "电子课本",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                Text("导入", fontSize = 14.sp)
            }
        }

        // ── 导入进行中 ──
        if (importingName.isNotEmpty() || importTotal > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = metrics.pagePadding, vertical = 2.dp)
                    .styleCard()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = if (importingName.isNotEmpty()) "正在导入：$importingName"
                    else "导入完成 $importDone / $importTotal",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.height(8.dp))
                if (importTotal > 0) {
                    LinearProgressIndicator(
                        progress = { importDone.toFloat() / importTotal },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (textbooks.isEmpty()) {
            EmptyState(
                onImport = { picker.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val grouped = remember(textbooks) { groupByCourse(textbooks) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = metrics.pagePadding,
                    end = metrics.pagePadding,
                    top = 6.dp,
                    bottom = 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(metrics.cardGap)
            ) {
                grouped.forEach { (course, books) ->
                    item(key = "header-$course") {
                        Text(
                            text = course.ifBlank { "未分类" },
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                        )
                    }
                    item(key = "group-$course") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .styleCard()
                        ) {
                            books.forEachIndexed { index, book ->
                                TextbookRow(
                                    textbook = book,
                                    onClick = { onOpen(book) },
                                    // 用户明确要求不要反复确认：长按即删，删完给条提示
                                    onLongClick = {
                                        onDelete(book)
                                        Toast.makeText(
                                            context,
                                            "已删除《${book.title}》",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                                if (index != books.lastIndex) {
                                    // 26.28 起全 App 不放列表分隔线，靠行高和内边距分段
                                    Spacer(Modifier.height(2.dp))
                                }
                            }
                        }
                    }
                }
                item(key = "hint") {
                    Text(
                        text = "长按某本书可以删除。教材只存在本机，不会上传。",
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                }
            }
        }
    }
}

/** 未分类永远排最后，其余按课程名排序 */
private fun groupByCourse(books: List<Textbook>): List<Pair<String, List<Textbook>>> {
    val map = books.groupBy { it.courseName }
    val matched = map.filterKeys { it.isNotBlank() }.toList().sortedBy { it.first }
    val unmatched = map.filterKeys { it.isBlank() }.toList()
    return matched + unmatched
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextbookRow(
    textbook: Textbook,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = textbook.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 2
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${textbook.pageLabel} · ${textbook.sizeLabel}",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        // 读过才显示百分比，没读过的书显示「未读」，比 0% 清楚
        Text(
            text = if (textbook.lastPage > 0) "${(textbook.progress * 100).toInt()}%" else "未读",
            fontSize = 11.sp,
            color = if (textbook.lastPage > 0) colors.primary
            else colors.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun EmptyState(onImport: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(horizontal = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "还没有教材",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "把上课要用的 PDF 教材导进来，系统会按文件名自动归到对应课程下。",
            fontSize = 12.sp,
            color = colors.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "选择 PDF 导入",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primary)
                .clickable(onClick = onImport)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}
