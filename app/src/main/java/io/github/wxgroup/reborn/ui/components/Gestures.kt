package io.github.wxgroup.reborn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * 连点保护的最短间隔：快于此值的第二次点击直接丢掉。
 *
 * ── 为什么需要它 ──
 * 很多回调是「写磁盘 + 刷新列表」，抖动式连点会让它执行两次：删同一分组两次、
 * 连开两个 Activity、主题切换动画叠两遍。以前靠每个页面自己加 flag，容易漏。
 * 现在所有 `clickable` 统一走这里，行为一致也不用各自记账。
 *
 * 取值参考：人手的最短连点间隔约 300ms，留一点余量取 400ms。
 */
private const val RAPID_CLICK_GUARD_MS = 400L

/**
 * 带连点保护的 [clickable]。
 *
 * 语义是「点击一次只执行一次回调」，不影响视觉反馈（涟漪照常走默认 indication）。
 */
fun Modifier.clickableOnce(
    onClick: () -> Unit,
    enabled: Boolean = true
): Modifier = composed {
    var lastClickAt by remember { mutableLongStateOf(0L) }
    clickable(enabled = enabled) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastClickAt < RAPID_CLICK_GUARD_MS) return@clickable
        lastClickAt = now
        onClick()
    }
}
