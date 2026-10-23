package io.github.wxgroup.reborn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.wxgroup.reborn.ui.theme.LocalAppColors
import io.github.wxgroup.reborn.ui.theme.WxShape
import io.github.wxgroup.reborn.ui.theme.WxSpace
import io.github.wxgroup.reborn.ui.util.rememberReducedMotion
import kotlin.math.roundToInt

/* ══════════════════════════════════════════════════════════════════════════════
 * 等高列表的拖动排序容器
 *
 * ── 前提 ──
 * 条目**等高**（本项目分组行天然等高），于是可以直接用「拖动位移 / 行高」算出挪了几格，
 * 不必去查 LazyColumn 的布局信息，也不需要引入 reorder 库。
 * 若将来出现不等高条目或需要拖到边缘自动滚动，再换成熟方案不迟。
 *
 * ── 反馈设计 ──
 * 拖动中：当前行抬起来（缩放 + 阴影 + 提到最上层），其余行保持不动 ——
 *         用户看到的是「这一块被拿起来了」，而不是整列表在抖。
 * 换位时：条目位置即时互换（写对应的 onMove），松手即落位，不需要额外的「放置线」。
 * 系统开了「移除动画」时缩放值为 1，不做任何动画。
 * ══════════════════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> DragSortList(
    items: List<T>,
    itemKey: (T) -> Any,
    itemHeight: Dp = 64.dp,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    onDragStateChange: ((dragging: Boolean) -> Unit)? = null,
    itemContent: @Composable (item: T, index: Int, dragging: Boolean, dragModifier: Modifier) -> Unit
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val heightPx = with(density) { itemHeight.toPx() }
    val reducedMotion = rememberReducedMotion()

    // -1 = 当前没有条目在被拖动
    var draggingIndex by remember { mutableIntStateOf(-1) }
    // 手指相对按下点的累计垂直位移（px）
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(WxSpace.sm),
        contentPadding = PaddingValues(horizontal = WxSpace.lg, vertical = WxSpace.sm)
    ) {
        itemsIndexed(items, key = { _, item -> itemKey(item) }) { index, item ->
            val isDragging = draggingIndex == index
            val scale by animateFloatAsState(
                targetValue = when {
                    !isDragging || reducedMotion -> 1f
                    else -> 1.03f
                },
                label = "dragScale"
            )
            val key = itemKey(item)
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
                        // 抬起来的观感：拖动时给一点阴影（0dp → 6dp）
                        shadowElevation = if (isDragging) 6f else 0f
                        shape = WxShape.card
                        clip = true
                        ambientShadowColor = colors.textPrimary
                        spotShadowColor = colors.textPrimary
                    }
                    .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
                    // key 变化时（列表换位）自动重启：闭包里的 index 始终是最新的
                    .pointerInput(key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffset = 0f
                                onDragStateChange?.invoke(true)
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
                                        // 换位后本条目已在新位置，把位移按格回退，
                                        // 否则位移会持续累积，表现为「越拖越快」
                                        dragOffset -= (if (steps > 0) 1 else -1) * heightPx
                                        draggingIndex = target
                                    } else {
                                        dragOffset = 0f
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                dragOffset = 0f
                                onDragStateChange?.invoke(false)
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffset = 0f
                                onDragStateChange?.invoke(false)
                            }
                        )
                    }
            )
        }
    }
}
