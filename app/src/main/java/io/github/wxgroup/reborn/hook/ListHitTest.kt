package io.github.wxgroup.reborn.hook

import android.view.View
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.xp.XposedHelpers

/** 点击解析结果类型 */
internal const val TAP_UNKNOWN = 0
internal const val TAP_FOLDER = 1
internal const val TAP_SECTION = 2
internal const val TAP_REAL = 3

internal data class TapResolution(
    val kind: Int,
    val groupId: String? = null,
    val view: View? = null,
    val position: Int = -1   // 纠正后的 ListView 位置（仅 TAP_REAL 有意义）
)

/** 取 ListView 在某个绝对位置上的子 View（含 header 偏移）；拿不到返回 null */
internal fun ConversationGroupHook.viewAt(lv: android.widget.ListView, pos: Int): View? {
    val c = pos - lv.firstVisiblePosition
    return if (c in 0 until lv.childCount) lv.getChildAt(c) else null
}

/**
 * 统一判定「这次点击/长按落在哪一行、是什么类型」。
 *
 * ⚠️ 这是修复「分组行点不动 / 二级页面第一个成员点不动」的核心。
 *
 * 微信 8.0.x 的会话列表在派发点击时给的 position 和 view 与被点的真实子 View 对不上
 * （实测差若干行，见 folderGroupOf 注释）。旧逻辑靠 view.tag 判断分组行，但 **view 本身就
 * 是错位的子 View** —— 点分组行时框架传进来的是真实会话行(kp5.s tag)，于是被当真实会话
 * 转发给微信、分组永远打不开；点二级页面第一个成员时框架传进来的是标题栏(FolderHolder)，
 * 于是被当成分组行把刚打开的分组又折叠回去（"要点好几次才进去"）。
 *
 * 这里**优先用按下探测 downPos**（ACTION_DOWN 时以屏幕坐标反算，绝对可靠，不受框架错位影响），
 * 直接去虚拟模型里查这一行到底是什么类型，并据此用「正确的子 View + 正确的位置」转发。
 * 只有探测不可用时（合成事件等）才退回旧的 tag / position 兜底逻辑。
 */
internal fun ConversationGroupHook.resolveTap(parent: Any?, fView: View?, fPos: Int): TapResolution {
    val lv = parent as? android.widget.ListView
    // 1) 优先：按下探测（屏幕坐标，绝对可靠，对抗"position 与 view 对不上"）
    if (lv != null && probeFresh()) {
        val idx = downPos - headerCount(lv)
        val e = legacyModel?.entries?.getOrNull(idx)
        return when (e?.type) {
            TYPE_HEADER -> {
                val gid = e.group?.id
                if (gid != null) TapResolution(TAP_FOLDER, gid, viewAt(lv, downPos) ?: fView, downPos)
                else TapResolution(TAP_UNKNOWN, view = fView, position = fPos)
            }
            TYPE_SECTION -> TapResolution(TAP_SECTION, view = viewAt(lv, downPos) ?: fView, position = downPos)
            TYPE_REAL -> TapResolution(TAP_REAL, view = viewAt(lv, downPos) ?: fView, position = downPos)
            else -> TapResolution(TAP_UNKNOWN, view = fView, position = fPos)
        }
    }
    // 2) 兜底：框架给的 tag / position（探测不可用时）
    val tag = fView?.tag
    if (tag is FolderHolder) {
        val gid = tag.groupId
        if (gid != null) return TapResolution(TAP_FOLDER, gid, fView, fPos)
    }
    if (lv != null) {
        val idx = fPos - headerCount(lv)
        val e = legacyModel?.entries?.getOrNull(idx)
        return when (e?.type) {
            TYPE_HEADER -> {
                val gid = e.group?.id
                if (gid != null) TapResolution(TAP_FOLDER, gid, fView, fPos)
                else TapResolution(TAP_UNKNOWN, view = fView, position = fPos)
            }
            TYPE_SECTION -> TapResolution(TAP_SECTION, view = fView, position = fPos)
            TYPE_REAL -> TapResolution(TAP_REAL, view = fView, position = fPos)
            else -> TapResolution(TAP_UNKNOWN, view = fView, position = fPos)
        }
    }
    return TapResolution(TAP_UNKNOWN, view = fView, position = fPos)
}

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
        if (DIAG_LOG) log("folderGroupOf: 命中 FolderHolder tag → 组行 gid=$gid (openGroupId=$openGroupId)")
        return sortedGroups().firstOrNull { it.id == gid }
    }
    // tag 是微信自己的行持有者（jo5.s）→ 铁定是真实会话行，直接放行
    if (tag != null) {
        // 记下「真实会话行」的持有者类型，供 canForwardToWeChat 判断 view 是否过期
        hostRowTagClass = tag.javaClass
        return null
    }
    // 只有在 View 压根没有 tag 时，才退回到「按下探测」/位置判断
    if (probeFresh()) {
        val gid = downGroupId ?: return null
        if (DIAG_LOG) log("folderGroupOf: tag=null 但探测命中组行 downGroupId=$gid（openGroupId=$openGroupId）")
        return sortedGroups().firstOrNull { it.id == gid }
    }
    if (position < 0) {
        if (DIAG_LOG) log("folderGroupOf: tag=null 且 position<0 → 放行(null)")
        return null
    }
    val idx = position - headerCount(parent)
    val e = legacyModel?.entries?.getOrNull(idx) ?: run {
        if (DIAG_LOG) log("folderGroupOf: tag=null, idx=$idx 越界 model=${legacyModel?.entries?.size} → 放行(null)")
        return null
    }
    if (DIAG_LOG) log("folderGroupOf: tag=null 回退 position=$position idx=$idx entry=${if (e.type == TYPE_HEADER) "H(${e.group?.id})" else "R(real=${e.realPos})"} openGroupId=$openGroupId")
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
 * 这个位置是不是「分组」小节标题行。
 * 小节行既不是会话也不是收纳组，任何点击/长按都要我们自己吃掉 ——
 * 交给微信会按会话行处理，拿不到会话就 NPE。
 */
internal fun ConversationGroupHook.isSectionPosition(parent: Any?, pos: Int): Boolean {
    val idx = pos - headerCount(parent)
    return legacyModel?.entries?.getOrNull(idx)?.type == TYPE_SECTION
}

/**
 * 这次点击/长按能不能**交回微信**处理？
 *
 * ── 为什么需要这个判断（真实闪退）──
 * 点进分组时虚拟模型从 17 行缩到 4 行，ListView 里可能还留着上一轮布局的旧子 View
 * （或者在列表末尾的 footer / 正在回收的行）。这种 View 上的 `tag` 是 null，
 * 但 `position` 仍在旧范围内，于是我们把它当普通会话行转发给微信：
 *
 *     java.lang.NullPointerException: Attempt to invoke virtual method
 *     'java.lang.CharSequence com.tencent.mm.ui.base.NoMeasuredTextView.getText()'
 *     on a null object reference
 *       at com.tencent.mm.ui.conversation.x3.onItemLongClick
 *       at io.github.wxgroup.reborn.hook.OurItemLongClickListener.onItemLongClick
 *
 * 微信的长按处理器直接拿 View 当会话行去 findViewById 子控件，拿到 null 就崩。
 * 这里做一次「视图 ↔ 模型」的一致性校验：对不上就**我们吃掉**这次事件，
 * 宁可这次点击无效，也绝不能把微信弄崩。
 */
internal fun ConversationGroupHook.canForwardToWeChat(parent: Any?, view: View?, position: Int): Boolean {
    if (view == null) return false
    val tag = view.tag
    // 收纳组行永远不交给微信
    if (tag is FolderHolder) {
        if (DIAG_LOG) log("canForward: 拒绝(FolderHolder) pos=$position openGroupId=$openGroupId")
        return false
    }

    val lv = parent as? android.widget.ListView ?: return true
    val hv = headerCount(lv)

    // ListView 自己的 header / footer（位置在虚拟条目之外）：微信自己认账，放行
    if (position < hv) return true
    // 超出当前适配器范围 = 模型刚缩小、这一行已经不存在了
    if (position >= lv.count) {
        if (DIAG_LOG) log("canForward: 拒绝(越界) pos=$position >= lv.count=${lv.count} hv=$hv openGroupId=$openGroupId")
        return false
    }

    // View 必须真的是 ListView 当前的子 View（旧布局残留的子 View 已被 detach）
    if (lv.indexOfChild(view) < 0) {
        if (DIAG_LOG) log("canForward: 拒绝(非子View) pos=$position indexOfChild=-1 openGroupId=$openGroupId")
        return false
    }

    val idx = position - hv
    val e = legacyModel?.entries?.getOrNull(idx) ?: run {
        if (DIAG_LOG) log("canForward: 拒绝(无entry) idx=$idx model=${legacyModel?.entries?.size} openGroupId=$openGroupId")
        return false
    }
    if (e.type != TYPE_REAL || e.realPos < 0) {
        if (DIAG_LOG) log("canForward: 拒绝(非REAL) idx=$idx type=${e.type} realPos=${e.realPos} openGroupId=$openGroupId")
        return false
    }

    // 真实会话行的 tag 是微信自己的行持有者；如果这一轮已经见过持有者类型，
    // tag 为 null 或类型不符就说明是过期 / 异型行，交回去必崩。
    val expect = hostRowTagClass
    if (expect != null && !expect.isInstance(tag)) {
        if (DIAG_LOG) log("canForward: 拒绝(tag不符) pos=$position expect=${expect.simpleName} actualTag=${tag?.javaClass?.name} openGroupId=$openGroupId")
        return false
    }

    if (DIAG_LOG) log("canForward: 放行 pos=$position idx=$idx realPos=${e.realPos} tag=${tag?.javaClass?.simpleName} openGroupId=$openGroupId")
    return true
}

/**
 * 列表项点击诊断。只在出现「点的行 ≠ 处理的行」时才需要它，
 * 所以前若干次打全量，之后打静默。
 */
internal fun ConversationGroupHook.logItemDispatch(view: View?, parent: Any?, pos: Int, what: String) {
    if (itemDispatchLogged >= 80) return
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
