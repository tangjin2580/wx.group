package io.github.wxgroup.reborn.hook

import android.view.View
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.xp.XposedHelpers

/**
 * 列表命中判定 —— 回答两个问题：
 *   1. 这个 ListView / 适配器是不是我们的会话列表？
 *   2. 这次点击/长按到底落在哪一行、是不是收纳组行？
 *
 * ⚠️ 全文件唯一可信的依据是**被点的那个 View 自己的 tag**，position 一律不可信
 * （详见 folderGroupOf 的注释）。
 */

/** 这个对象是不是会话列表（ListView 本身或它的适配器是我们认得的那个） */
internal fun ConversationGroupHook.isOurListView(o: Any?): Boolean {
    val lv = o as? android.widget.ListView ?: return false
    val ref = legacyListView?.get()
    if (ref != null) return ref === lv
    return adapterIsOurs(lv.adapter)
}

/** 适配器是否是我们接管的那一个（ListView 加过 header/footer 时会套一层 HeaderViewListAdapter） */
internal fun ConversationGroupHook.adapterIsOurs(a: Any?): Boolean {
    if (a == null) return false
    if (legacyAdapterClass?.isInstance(a) == true) return true
    val inner = try {
        XposedHelpers.getObjectField(a, "mAdapter")
    } catch (t: Throwable) {
        null
    }
    return legacyAdapterClass?.isInstance(inner) == true
}

/** ListView 的 header view 数量（position 是「含 header」的列表位置） */
internal fun ConversationGroupHook.headerCount(o: Any?): Int = try {
    (o as? android.widget.ListView)?.headerViewsCount ?: 0
} catch (t: Throwable) {
    0
}
/**
 * 判断「这次点击/长按落在哪个收纳组行上」，不是收纳组行就返回 null。
 *
 * ⚠️⚠️ 唯一可信的依据是**被点的那个 View 自己的 tag**（收纳组行的 tag 是我们放的
 * FolderHolder），**绝不能看 position**。
 *
 * 实测（8.0.78）铁证：
 *     按下探测:   rawY=726, childIndex=15, pos=15  → 用户按的是第 15 行
 *     点击放行:   position=33, posForView=15       → 微信报的位置却是 33
 *     结果:       WeChat getItem(33-14=19) → 打开了完全不相干的会话
 * 也就是说这个版本里 `AbsListView` 派发点击时带的 position 和被点的子 View **对不上**
 * （差 18 行）。用户因此看到两种怪象：
 *   · 点分组下面的会话，那个 position 恰好落在收纳组行的条目上 → 分组被展开/折叠
 *   · 点真正的分组行，position 落到别的条目上 → 「点不开」
 * 位置完全不可信，只有 View 是可信的。
 */
internal fun ConversationGroupHook.folderGroupOf(view: View?, parent: Any?, position: Int): WxGroup? {
    val tag = view?.tag
    if (tag is FolderHolder) {
        val gid = tag.groupId ?: return null
        return sortedGroups().firstOrNull { it.id == gid }
    }
    // tag 是微信自己的行持有者（jo5.s）→ 铁定是真实会话行，直接放行
    if (tag != null) return null
    // 只有在 View 压根没有 tag 时，才退回到「按下探测」/位置判断
    if (probeFresh()) {
        val gid = downGroupId ?: return null
        return sortedGroups().firstOrNull { it.id == gid }
    }
    if (position < 0) return null
    val e = legacyModel?.entries?.getOrNull(position - headerCount(parent)) ?: return null
    return if (e.type == TYPE_HEADER && e.group != null) e.group else null
}

/**
 * 算出「被点的这个 View 在 ListView 里的真实 position」。
 *
 * 微信给的 position 不可信（见 folderGroupOf 的注释），而 `getPositionForView(view)`
 * = mFirstPosition + indexOfChild(view)，是**对这个 View 现算的**，一定对得上。
 * 这个值还要**回写给微信**：它的 `getItem(position - headerViewsCount)` 就是靠它找会话的，
 * 不纠正的话点哪个会话就会打开别的会话。
 */
internal fun ConversationGroupHook.truePosition(parent: Any?, view: View?, fallback: Int): Int {
    val lv = parent as? android.widget.ListView ?: return fallback
    val p = try {
        lv.getPositionForView(view)
    } catch (t: Throwable) {
        -1
    }
    if (p >= 0 && p < lv.count) return p
    if (probeFresh()) return downPos
    return fallback
}

/**
 * 列表项点击诊断。只在出现「点的行 ≠ 处理的行」时才需要它，
 * 所以前若干次打全量，之后打静默。
 */
internal fun ConversationGroupHook.logItemDispatch(view: View?, parent: Any?, pos: Int, what: String) {
    if (itemDispatchLogged >= 25) return
    itemDispatchLogged++
    val tag = when (val t = view?.tag) {
        null -> "null"
        is FolderHolder -> "FolderHolder(${t.groupId})"
        else -> t.javaClass.simpleName
    }
    val lv = parent as? android.widget.ListView
    val hv = lv?.headerViewsCount ?: 0
    val fv = lv?.firstVisiblePosition ?: -1
    val pfv = try {
        lv?.getPositionForView(view)
    } catch (t: Throwable) {
        -1
    }
    val ci = try {
        if (lv != null && view != null) lv.indexOfChild(view) else -1
    } catch (t: Throwable) {
        -1
    }
    val m = legacyModel
    val eAt = { i: Int -> m?.entries?.getOrNull(i)?.let { if (it.type == TYPE_HEADER) "H" else "R" } ?: "-" }
    log(
        "列表项点击: pos=$pos → $what | header=$hv, firstVisible=$fv, childIndex=$ci, posForView=$pfv, " +
            "entry(pos)=${eAt(pos)}, entry(pos-header)=${eAt(pos - hv)}, " +
            "view=${view?.javaClass?.simpleName}, tag=$tag"
    )
}
