package io.github.wxgroup.reborn.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import io.github.wxgroup.reborn.ui.state.GroupUi
import io.github.wxgroup.reborn.ui.theme.LocalAppColors

/** 一行分组条目的固定高度（拖动排序按等高计算，见 [DragSortList] 注释） */
private val GroupRowHeight = 64.dp

/**
 * 分组管理页。
 *
 * 从上到下：新建入口 → 分组列表（可拖动排序 / 左滑操作）→ 空分组提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(
    groups: List<GroupUi>,
    onCreate: () -> Unit,
    onRename: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onToggleMute: (String) -> Unit,
    onMembers: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    contentPadding: PaddingValues
) {
    val colors = LocalAppColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBg)
            .padding(contentPadding)
    ) {
        // ── 新建分组入口 ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = io.github.wxgroup.reborn.ui.theme.CardShape,
            colors = CardDefaults.cardColors(containerColor = colors.cardBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.cardStroke),
            onClick = onCreate
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                io.github.wxgroup.reborn.ui.components.IconBox(
                    icon = Icons.Rounded.Add,
                    tint = io.github.wxgroup.reborn.ui.theme.IconBlue
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "新建分组",
                    style = MaterialTheme.typography.titleMedium,
                    color = io.github.wxgroup.reborn.ui.theme.Mint40,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "还没有分组，点上方创建一个",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        } else {
            DragSortList(
                items = groups,
                itemHeight = GroupRowHeight,
                itemKey = { it.id },
                onMove = onMove,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { item, _index, dragging, dragModifier ->
                GroupRow(
                    group = item,
                    dragging = dragging,
                    dragModifier = dragModifier,
                    onRename = { onRename(item.id) },
                    onTogglePin = { onTogglePin(item.id) },
                    onToggleMute = { onToggleMute(item.id) },
                    onMembers = { onMembers(item.id) },
                    onDelete = { onDelete(item.id) }
                )
            }
        }
    }
}

/**
 * 单行分组：左滑删除 / 右滑重命名，置顶与免打扰走行内小图标。
 *
 * 滑动手势用 Material 3 的 [SwipeToDismissBox]：confirmValueChange 里触发动作后
 * 返回 false，让它弹回原位（界面里「条目不该消失，只是执行一个动作」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupRow(
    group: GroupUi,
    dragging: Boolean,
    dragModifier: Modifier,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onMembers: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalAppColors.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onRename(); false }  // 右滑：重命名
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); false } // 左滑：删除
                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // 滑动时露出的背景：这里只做极淡的底色 + 图标，不做花哨动效
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.pageBg)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 20.dp)
                        .size(18.dp)
                )
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 20.dp)
                        .size(18.dp)
                )
            }
        }
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = io.github.wxgroup.reborn.ui.theme.CardShape,
            colors = CardDefaults.cardColors(containerColor = colors.cardBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.cardStroke)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GroupRowHeight)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标：单字符号 + 本组强调色（沉浸感的关键）
                io.github.wxgroup.reborn.ui.components.GlyphBox(
                    glyph = group.icon,
                    tint = Color(group.iconTint)
                )
                Spacer(Modifier.width(12.dp))

                // 名称 + 副标题（副标题承载「成员数/未读/免打扰」，避免正文堆字）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onMembers),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        fontWeight = if (group.pinned) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Text(
                        text = buildString {
                            append(group.memberCount).append(" 个成员")
                            if (group.unread > 0) append(" · ").append(group.unread).append(" 条未读")
                            if (group.muted) append(" · 免打扰")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }

                // 未读角标（恒红，与主题无关）
                if (group.unread > 0) {
                    UnreadBadge(count = group.unread)
                    Spacer(Modifier.width(4.dp))
                }

                // 免打扰
                IconButton(onClick = onToggleMute, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.VolumeOff,
                        contentDescription = "免打扰",
                        tint = if (group.muted) io.github.wxgroup.reborn.ui.theme.Mint40
                    else colors.textSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                // 置顶
                IconButton(onClick = onTogglePin, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = "置顶",
                        tint = if (group.pinned) io.github.wxgroup.reborn.ui.theme.Mint40
                    else colors.textSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 拖动把手：长按后上下拖到目标位置松手换序
                Box(
                    modifier = dragModifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = "拖动排序",
                        tint = colors.textSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 极简拖动排序容器。
 *
 * 前提：所有条目**等高**（本模块分组行天然等高），于是可以直接用
 * 「拖动位移 / 条目高度」算出要挪几格，不必去查 LazyColumn 的布局信息。
 * 若将来条目不等高、或需要拖到边缘自动滚动，建议换成
 * `org.burnoutcrew.composereorderable` 这类成熟库。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> DragSortList(
    items: List<T>,
    itemHeight: Dp,
    itemKey: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, index: Int, dragging: Boolean, dragModifier: Modifier) -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val heightPx = with(density) { itemHeight.toPx() }

    // -1 表示当前没有条目在被拖动
    var draggingIndex by remember { mutableIntStateOf(-1) }
    // 手指相对按下点的累计垂直位移（px）
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        itemsIndexed(items, key = { _, item -> itemKey(item) }) { index, item ->
            val isDragging = draggingIndex == index
            val scale by animateFloatAsState(
                targetValue = if (isDragging) 1.03f else 1f,
                label = "dragScale"
            )
            itemContent(
                item,
                index,
                isDragging,
                Modifier
                    // 拖动中的条目提到最上层，避免被相邻行盖住
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
                    .pointerInput(itemKey(item)) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                                // 位移换算成「挪了几格」，超过半格就算换位
                                val steps = kotlin.math.round(dragOffset / heightPx).toInt()
                                if (steps != 0) {
                                    val target = (index + steps).coerceIn(0, items.size - 1)
                                    if (target != index) {
                                        onMove(index, target)
                                        // 换位后本条目已经在新位置，把位移按格回退，
                                    // 否则位移会持续累积，表现为「越拖越快」
                                        dragOffset -= (if (steps > 0) 1 else -1) * heightPx
                                        draggingIndex = target
                                    } else {
                                        dragOffset = 0f
                                    }
                                }
                            },
                            onDragEnd = { draggingIndex = -1; dragOffset = 0f },
                            onDragCancel = { draggingIndex = -1; dragOffset = 0f }
                        )
                    }
            )
        }
    }
}

/** 未读角标（恒红，与主题无关 —— 用户靠红色识别未读） */
@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .width(if (count > 9) 28.dp else 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFA5151)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
