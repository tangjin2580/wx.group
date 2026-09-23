package io.github.wxgroup.reborn.hook

import android.view.View
import io.github.wxgroup.reborn.xp.XC_MethodHook
import io.github.wxgroup.reborn.xp.XC_MethodHook.MethodHookParam
import io.github.wxgroup.reborn.xp.XposedBridge

/**
 * 按下位置探测 —— 本次修复的核心。
 *
 * 微信 8.0.78 的会话列表 ListView 在派发点击时给的 position 与被点的子 View 对不上
 * （实测差 18 行），所以这里在 ACTION_DOWN 时用**屏幕坐标**自己算一次，
 * 后续 performItemClick / performLongPress 都以这个结果为准。
 */

// ==================================================================================
// 按下位置探测 —— 本次修复的核心
//
// 实测（8.0.78）：会话列表 ListView 的 headerViewsCount = 14，我们的收纳组行是
// ListView 的第 14、15 行。ListView 自己的命中判定（AdapterView.pointToPosition）
// 用的是「相对 ListView 的 y」去比「子 View 的 hitRect（也相对 ListView）」，
// 但这条链路上两者会错开整整一行 —— 现象就是：
//     手指按在「第 2 行（某个会话）」上，ListView 报的却是「第 1 行（收纳组行）」，
//     于是把分组展开/折叠了，而真正该打开的那个会话没反应。
// 用户原话：「点击发际线保护协会 就变成展开、收纳了」。
//
// 解法：不信它的 position，自己在 ACTION_DOWN 时用 **屏幕坐标** 比 **子 View 自己的
// getLocationOnScreen 矩形**，算出用户真正按的是哪一个子 View。getLocationOnScreen
// 和实际绘制用的是同一套变换，所以这个判定必然和用户眼睛看到的一致。
// ==================================================================================
internal fun ConversationGroupHook.installTouchDownProbe() {
    val me = android.view.MotionEvent::class.java
    val hooked = try {
        val m = android.widget.AbsListView::class.java.getDeclaredMethod("onTouchEvent", me)
        m.isAccessible = true
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                probeTouch(param)
            }
        })
        true
    } catch (t: Throwable) {
        log("onTouchEvent 探测挂载失败: ${t.message}")
        false
    }
    try {
        val m = android.widget.AbsListView::class.java
            .getDeclaredMethod("onInterceptTouchEvent", me)
        m.isAccessible = true
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                probeTouch(param)
            }
        })
    } catch (t: Throwable) {
        log("onInterceptTouchEvent 探测挂载失败: ${t.message}")
    }
    if (hooked) log("已挂载 Hook: AbsListView.onTouchEvent（按下位置探测）")
}

internal fun ConversationGroupHook.probeTouch(param: MethodHookParam) {
    try {
        val lv = param.thisObject as? android.widget.ListView ?: return
        if (!isOurListView(lv)) return
        val ev = param.args.getOrNull(0) as? android.view.MotionEvent ?: return
        if (ev.actionMasked != android.view.MotionEvent.ACTION_DOWN) return
        probeDown(lv, ev.rawY)
    } catch (t: Throwable) {
        log("按下探测异常: ${t.javaClass.simpleName}: ${t.message}")
    }
}

/** 用屏幕坐标自己判定按在哪一行，存起来给 performItemClick / performLongPress 用 */
internal fun ConversationGroupHook.probeDown(lv: android.widget.ListView, rawY: Float) {
    val loc = IntArray(2)
    var idx = -1
    for (i in 0 until lv.childCount) {
        val c = lv.getChildAt(i)
        if (c.height <= 0) continue
        c.getLocationOnScreen(loc)
        if (rawY >= loc[1] && rawY < loc[1] + c.height) {
            idx = i
            break
        }
    }
    downChildIndex = idx
    downPos = if (idx >= 0) lv.firstVisiblePosition + idx else -1
    val t = if (idx >= 0) lv.getChildAt(idx).tag else null
    downGroupId = (t as? FolderHolder)?.groupId
    downAt = android.os.SystemClock.uptimeMillis()
    if (downLogCount < 3) {
        downLogCount++
        val kind = when (t) {
            is FolderHolder -> "FolderHolder(${t.groupId})"
            null -> "null"
            else -> t.javaClass.simpleName
        }
        log("按下探测: rawY=$rawY, childIndex=$idx, pos=$downPos, tag=$kind")
    }
}

/**
 * 按下探测结果是否可信（2 秒内的最近一次按下）。
 * 只要可信，就**完全以它为准**，不再用 ListView 报的 position —— 那个会错一行。
 */
internal fun ConversationGroupHook.probeFresh(): Boolean =
    downPos >= 0 && android.os.SystemClock.uptimeMillis() - downAt < 2000L

/** 把 ListView 给错的 position 纠正成「被点 View 的真实 position」 */
internal fun ConversationGroupHook.correctTapArgs(param: MethodHookParam) {
    val lv = param.thisObject as? android.widget.ListView ?: return
    val view = param.args.getOrNull(0) as? View
    val old = param.args.getOrNull(1) as? Int ?: return
    val np = truePosition(lv, view, old)
    if (np == old) return
    if (correctLogged < 12) {
        correctLogged++
        log("纠正列表点击位置: 微信报 $old → 实际行为 $np（view=${view?.javaClass?.simpleName}）")
    }
    param.args[1] = np
}
