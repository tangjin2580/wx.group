package io.github.wxgroup.reborn.hook

import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ListView
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.xp.XC_MethodHook
import io.github.wxgroup.reborn.xp.XC_MethodHook.MethodHookParam
import io.github.wxgroup.reborn.xp.XposedBridge

/**
 * 微信内拖动排序（不跳模块 App）。
 *
 * ── 为什么不用 DragSortListView / ItemTouchHelper ──────────────────
 * 会话列表是微信自己的 ListView + 定制 Adapter，我们只是「虚拟行」的旁观者；
 * 不能替换 Adapter、不能改 ListView 的布局，只能在 framework 的
 * `AbsListView.onTouchEvent` 上做一层拦截：
 *
 *   进入排序模式 → 所有触摸由本文件接管
 *   按下组行     → 记录拖动目标
 *   移动超 slop  → 创建一个「悬浮行」挂到 ListView 的 overlay 上跟着手指走，
 *                  同时让落点区间的其它行整体让位（translationY），并画一条微信绿指示线
 *   拖到边缘     → 列表自动滚动（长列表也能拖到底）
 *   抬起         → 悬浮行滑进目标槽位 + 淡出，然后重排、写盘、退出排序模式
 *   点空白       → 直接退出
 *
 * overlay 是 `ViewGroupOverlay`：里面的视图不参与布局、绘制在所有子 View 之上，
 * 正好用来做拖拽层，也不会影响微信自己的列表测量。
 *
 * ── 让位动画为什么是「整段平移」而不是只动组行 ──
 * 拖动一行之后，源位置和目标位置之间的**所有行**（含普通会话行）都会上移/下移
 * 一个「被拖行高度」。把这些行按同样的位移做 translationY，就是最终布局的
 * 像素级预览 —— 松手时列表重排不会跳，看起来就是「行自己让开、又合上」。
 */

/** 可见组行的位置：分组 id、适配器位置、上沿、下沿（ListView 视口坐标） */
private class HeaderSpan(val gid: String, val pos: Int, val top: Int, val bottom: Int)

internal fun ConversationGroupHook.installReorderTouchHook() {
    try {
        // 注意：ListView **没有**重写 onTouchEvent（实测 getDeclaredMethod 直接 NoSuchMethod），
        // 实现在父类 AbsListView 上。挂在 AbsListView 才能拦到会话列表的触摸。
        val m = android.widget.AbsListView::class.java
            .getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
        m.isAccessible = true
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!reorderMode) return
                val lv = param.thisObject as? ListView ?: return
                if (!isOurListView(lv)) return
                val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                try {
                    handleReorderTouch(lv, ev)
                } catch (t: Throwable) {
                    log("拖动排序触摸处理异常: ${t.javaClass.simpleName}: ${t.message}")
                    cancelDrag()
                    resetDragState()
                }
                // 排序模式下由我们独占触摸：不让 ListView 滚动 / 触发 item click
                param.result = true
            }
        })
        log("已挂载 Hook: AbsListView.onTouchEvent（微信内拖动排序）")
    } catch (t: Throwable) {
        log("拖动排序 Hook 挂载失败: ${t.message}")
    }
}

// ==================================================================================
// 进入 / 退出
// ==================================================================================
internal fun ConversationGroupHook.enterReorderMode() {
    if (inGroupPage) closeGroupPage()
    if (reorderMode) return
    reorderMode = true
    groupsRev++
    legacyModelKey = null
    notifyList()
    toast(appContext, "拖动分组调整顺序 · 点空白处或按返回结束")
    log("进入拖动排序模式")
}

internal fun ConversationGroupHook.exitReorderMode() {
    if (!reorderMode) return
    cancelDrag()
    resetDragState()
    reorderMode = false
    legacyModelKey = null
    notifyList()
    log("退出拖动排序模式")
}

// ==================================================================================
// 触摸处理
// ==================================================================================
private fun ConversationGroupHook.handleReorderTouch(lv: ListView, ev: MotionEvent) {
    when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            dragDownY = ev.y
            dragLastTouchY = ev.y
            dragOffsetY = 0f
            dragging = false
            dragTargetIndex = -1
            dragSlotTop = -1f
            val child = headerChildAt(lv, ev.y)
            if (child == null) {
                dragGroupId = null
                return
            }
            val holder = child.tag as? FolderHolder
            dragGroupId = holder?.groupId
            dragFromIndex = sortedGroups().indexOfFirst { it.id == holder?.groupId }
            dragOffsetY = ev.y - child.top
            dragRowHeight = child.height.takeIf { it > 0 } ?: dp(lv.context, 64)
            dragSourceRow = child
        }

        MotionEvent.ACTION_MOVE -> {
            dragLastTouchY = ev.y
            val gid = dragGroupId ?: return
            if (!dragging) {
                if (kotlin.math.abs(ev.y - dragDownY) < dp(lv.context, 8)) return
                val group = sortedGroups().firstOrNull { it.id == gid } ?: return
                beginDrag(lv, group)
                maybeStartAutoScroll(lv)
            }
            moveDrag(lv, ev.y)
        }

        MotionEvent.ACTION_UP -> {
            stopAutoScroll()
            if (dragging) {
                commitDrag(lv)
            } else {
                if (dragGroupId == null) exitReorderMode()      // 点在空白区域
                resetDragState()
            }
        }

        MotionEvent.ACTION_CANCEL -> {
            stopAutoScroll()
            cancelDrag()
            resetDragState()
        }
    }
}

/** 命中测试：ev.y（ListView 视口坐标）落在哪个「收纳组行」上；null = 空白或真实会话行 */
private fun ConversationGroupHook.headerChildAt(lv: ListView, y: Float): View? {
    for (i in 0 until lv.childCount) {
        val c = lv.getChildAt(i) ?: continue
        if (c.top <= y && c.bottom >= y && c.tag is FolderHolder) return c
    }
    return null
}

// ==================================================================================
// 拖动过程
// ==================================================================================
private fun ConversationGroupHook.beginDrag(lv: ListView, group: WxGroup) {
    dragging = true
    dragFromIndex = sortedGroups().indexOfFirst { it.id == group.id }
    val ctx = lv.context
    val source = dragSourceRow
    val rowH = source?.height?.takeIf { it > 0 } ?: dp(ctx, 64)
    dragRowHeight = rowH

    // 悬浮行：内容和真实行一致，抬起前看起来就是「把这一行拎起来了」
    val floating = createFolderRow(ctx).apply {
        isClickable = false
        isLongClickable = false
        bindFolderRow(this, group, group.members.size)
        (tag as? FolderHolder)?.arrow?.text = "≡"   // 排序态提示
        elevation = dp(ctx, 12).toFloat()
        alpha = 0f
        scaleX = 0.98f
        scaleY = 0.98f
    }
    floating.measure(
        View.MeasureSpec.makeMeasureSpec(lv.width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(rowH, View.MeasureSpec.EXACTLY)
    )
    floating.layout(0, 0, lv.width, rowH)
    // 从被拖行原来的位置开始，视觉上就是「这一行被原地拎起」
    floating.y = (source?.top ?: 0).toFloat()

    // 插入指示线（微信绿）：先加，保证画在悬浮行下面
    val indicator = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 2).toFloat()
            setColor(0xFF07C160.toInt())
        }
        alpha = 0f
        visibility = View.INVISIBLE
    }
    indicator.layout(0, 0, lv.width, dp(ctx, 3))
    lv.overlay.add(indicator)
    lv.overlay.add(floating)
    dragIndicator = indicator
    dragFloating = floating

    // 被拖行本体隐藏：让让位的其它行能「盖」过它的位置，而不是和它叠在一起
    source?.animate()?.alpha(0f)?.setDuration(120L)?.start()
    floating.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120L).start()
    lv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    log("开始拖动: ${group.name} (from=$dragFromIndex)")
}

private fun ConversationGroupHook.moveDrag(lv: ListView, y: Float) {
    val floating = dragFloating ?: return
    val h = floating.height.takeIf { it > 0 } ?: dragRowHeight.takeIf { it > 0 } ?: dp(lv.context, 64)
    val top = (y - dragOffsetY).coerceIn(0f, (lv.height - h).coerceAtLeast(0).toFloat())
    floating.y = top

    val spans = visibleHeaders(lv)
    if (spans.isEmpty()) return
    val dragged = sortedGroups().firstOrNull { it.id == dragGroupId } ?: return
    // 只在「同为置顶 / 同为非置顶」的组之间换位：sortForDisplay 里置顶恒在前，
    // 跨区拖动会被排序规则弹回去，体验上像是「拖不动」。
    val candidates = spans.filter { s ->
        val g = sortedGroups().firstOrNull { it.id == s.gid } ?: return@filter false
        g.id != dragged.id && g.pinned == dragged.pinned
    }
    if (candidates.isEmpty()) {
        dragIndicator?.animate()?.alpha(0f)?.setDuration(80L)?.start()
        dragSlotTop = -1f
        return
    }

    val center = top + h / 2f
    val before = candidates.firstOrNull { center < (it.top + it.bottom) / 2f }
    val groups = sortedGroups()
    val newTarget = if (before == null) {
        groups.size
    } else {
        groups.indexOfFirst { it.id == before.gid }.takeIf { it >= 0 } ?: groups.size
    }
    // 换位瞬间给一次轻微震动，手感上「咔哒」一下（微信自己也是这个反馈）
    val targetChanged = newTarget != dragTargetIndex
    dragTargetIndex = newTarget

    // 目标插入位置（适配器坐标）：插在 before 之前，或最后一个同区组之后
    val fromPos = dragSourceRow?.let { lv.getPositionForView(it) } ?: -1
    val toPos = before?.pos ?: (candidates.last().pos + 1)
    if (fromPos >= 0 && targetChanged) {
        applyGapShift(lv, fromPos, toPos)
    }

    // 落点槽位的 Y：向上拖时槽位就在 before 的上沿；
    // 向下拖时其它行整体上移了一行，槽位在 before 上沿再往上一个行高。
    dragSlotTop = when {
        before == null -> (candidates.last().bottom - h).toFloat()
        toPos > fromPos -> (before.top - h).toFloat()
        else -> before.top.toFloat()
    }
    if (targetChanged) lv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    // 指示线平滑跟随（只改 translationY，避免每帧重新 layout）
    val indicator = dragIndicator ?: return
    val lineY = (before?.top ?: candidates.last().bottom).toFloat()
    indicator.visibility = View.VISIBLE
    indicator.animate().cancel()
    indicator.animate()
        .translationY(lineY)
        .alpha(1f)
        .setDuration(90L)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

/**
 * 让位动画：把「源位置 → 目标位置」之间的所有可见行整体平移一个行高。
 *
 * 平移量与列表重排后的真实布局完全一致，所以松手时不会跳。
 */
private fun ConversationGroupHook.applyGapShift(lv: ListView, fromPos: Int, toPos: Int) {
    val rowH = dragRowHeight.takeIf { it > 0 } ?: return
    for (i in 0 until lv.childCount) {
        val c = lv.getChildAt(i) ?: continue
        if (c === dragSourceRow) continue
        val p = try { lv.getPositionForView(c) } catch (t: Throwable) { -1 }
        if (p < 0) continue
        val dy = when {
            toPos > fromPos && p > fromPos && p < toPos -> -rowH
            toPos < fromPos && p >= toPos && p < fromPos -> rowH
            else -> 0
        }
        if (c.translationY != dy.toFloat()) {
            c.animate().cancel()
            c.animate()
                .translationY(dy.toFloat())
                .setDuration(110L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}

/** 可见的收纳组行（含上下沿），按列表顺序 */
private fun ConversationGroupHook.visibleHeaders(lv: ListView): List<HeaderSpan> {
    val out = ArrayList<HeaderSpan>(lv.childCount)
    for (i in 0 until lv.childCount) {
        val c = lv.getChildAt(i) ?: continue
        val h = c.tag as? FolderHolder ?: continue
        val gid = h.groupId ?: continue
        val pos = try { lv.getPositionForView(c) } catch (t: Throwable) { -1 }
        out.add(HeaderSpan(gid, pos, c.top, c.bottom))
    }
    out.sortBy { it.top }
    return out
}

// ==================================================================================
// 边缘自动滚动：手指停在列表上下边缘时，列表持续滚动
// ==================================================================================
private fun ConversationGroupHook.maybeStartAutoScroll(lv: ListView) {
    if (dragAutoScroll != null) return
    val step = dp(lv.context, 9)
    val edge = dp(lv.context, 56)
    val r = object : Runnable {
        override fun run() {
            if (!dragging) {
                dragAutoScroll = null
                return
            }
            try {
                val y = dragLastTouchY
                val dy = when {
                    y < edge -> -step
                    y > lv.height - edge -> step
                    else -> 0
                }
                if (dy != 0) {
                    lv.scrollListBy(dy)
                    // 滚动后子 View 变了，重新算落点与让位
                    moveDrag(lv, y)
                }
            } catch (t: Throwable) {
                dragAutoScroll = null
                return
            }
            mainHandler.postDelayed(this, 16L)
        }
    }
    dragAutoScroll = r
    mainHandler.post(r)
}

private fun ConversationGroupHook.stopAutoScroll() {
    dragAutoScroll?.let { mainHandler.removeCallbacks(it) }
    dragAutoScroll = null
}

// ==================================================================================
// 落定
// ==================================================================================
private fun ConversationGroupHook.commitDrag(lv: ListView) {
    val gid = dragGroupId ?: return run { cancelDrag() }
    stopAutoScroll()

    val groups = sortedGroups().toMutableList()
    val from = groups.indexOfFirst { it.id == gid }
    var to = dragTargetIndex
    if (to < 0) to = groups.size
    if (to > from) to -= 1
    to = to.coerceIn(0, groups.size - 1)
    val changed = from >= 0 && to != from

    var savedOk = true
    if (changed) {
        val g = groups.removeAt(from)
        groups.add(to, g)
        // 顺序写进 order 字段（从 1 开始），与模块内排序页的持久化格式一致
        groups.forEachIndexed { i, item -> item.order = i + 1 }
        savedOk = GroupStore.saveGroupsXposed(groups)
        log("拖动排序: ${g.name} $from -> $to, 写盘=${if (savedOk) "成功" else "失败"}")
        synchronized(groupCache) {
            groupCache.clear()
            cacheTs.clear()
        }
    } else {
        log("拖动排序: 位置未变化")
    }

    // 落定动画：悬浮行滑进槽位并淡出，动画结束后才刷新列表 ——
    // 否则列表会在手指下「瞬移」一下，看起来像点错了。
    val floating = dragFloating
    if (floating == null) {
        finishDrop(lv, changed, savedOk)
        return
    }
    floating.animate().cancel()
    floating.animate()
        .y(if (dragSlotTop >= 0f) dragSlotTop else floating.y)
        .alpha(0f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(if (changed) 170L else 130L)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction { finishDrop(lv, changed, savedOk) }
        .start()
}

/** 落定收尾：清掉拖拽层、还原让位、刷新列表、退出排序模式 */
private fun ConversationGroupHook.finishDrop(lv: ListView, changed: Boolean, savedOk: Boolean) {
    dragFloating?.let { v ->
        try { (v.parent as? ViewGroup)?.removeView(v) } catch (_: Throwable) {}
    }
    dragIndicator?.let { v ->
        try { (v.parent as? ViewGroup)?.removeView(v) } catch (_: Throwable) {}
    }
    dragFloating = null
    dragIndicator = null
    restoreRows(lv)
    dragging = false

    if (changed) {
        groupsRev++
        legacyModelKey = null
        notifyList()
        toast(appContext, if (savedOk) "顺序已保存" else "顺序已保存（写盘失败，重启后可能丢失）")
    }
    resetDragState()
    // 给落定动画一点收尾时间再退出模式，避免列表在动画中途重建
    mainHandler.postDelayed({ if (reorderMode) exitReorderMode() }, if (changed) 180L else 0L)
}

private fun ConversationGroupHook.cancelDrag() {
    stopAutoScroll()
    dragFloating?.let { v ->
        v.animate().cancel()
        try { (v.parent as? ViewGroup)?.removeView(v) } catch (_: Throwable) {}
    }
    dragIndicator?.let { v ->
        v.animate().cancel()
        try { (v.parent as? ViewGroup)?.removeView(v) } catch (_: Throwable) {}
    }
    dragFloating = null
    dragIndicator = null
    restoreRows(null)
    dragging = false
}

/** 还原所有「让位」位移和被拖行的透明度 */
private fun ConversationGroupHook.restoreRows(lv: ListView?) {
    dragSourceRow?.let { src ->
        src.animate().cancel()
        src.animate().alpha(1f).setDuration(120L).start()
    }
    dragSourceRow = null
    if (lv != null) {
        for (i in 0 until lv.childCount) {
            val c = lv.getChildAt(i) ?: continue
            if (c.translationY != 0f) {
                c.animate().cancel()
                c.animate().translationY(0f).setDuration(130L).start()
            }
        }
    }
}

private fun ConversationGroupHook.resetDragState() {
    dragGroupId = null
    dragFromIndex = -1
    dragTargetIndex = -1
    dragOffsetY = 0f
    dragSlotTop = -1f
    dragRowHeight = 0
    dragging = false
}
