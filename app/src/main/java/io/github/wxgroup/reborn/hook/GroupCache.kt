package io.github.wxgroup.reborn.hook

import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.xp.XposedHelpers
import java.lang.ref.WeakReference

/**
 * 分组数据：读取缓存、变更检测与自动刷新、落盘、虚拟模型失效与列表重绘。
 *
 * 模块 App 改完分组后写镜像文件，微信侧靠 groupsStamp() 的变化发现它，
 * 然后主动 notifyDataSetChanged —— 不用杀微信也能立刻看到效果。
 */

/** 从微信进程直接落盘分组（写微信私有目录文件 + 镜像），并让会话列表立刻刷新 */
internal fun ConversationGroupHook.persistGroups(all: List<WxGroup>) {
    try {
        GroupStore.saveGroupsXposed(all)
    } catch (t: Throwable) {
        log("保存分组失败: ${t.message}")
    }
    synchronized(groupCache) {
        groupCache.clear()
        cacheTs.clear()
    }
    lastStamp = GroupStore.groupsStamp() // 是我们自己写的，别当成外部变更再来一轮
    legacyModelKey = null
    notifyList()
    log("分组已保存（微信侧）: ${all.joinToString { "${it.name}(${it.members.size})" }}")
}
// ==================================================================================
// 自动刷新 / 分组缓存
// ==================================================================================
internal fun ConversationGroupHook.checkAutoRefresh(adapter: Any) {
    adapterRef = WeakReference(adapter)
    if (!pollScheduled) {
        pollScheduled = true
        mainHandler.postDelayed(pollRunnable, 1200L)
    }
    detectAndRefresh(adapter)
}

internal fun ConversationGroupHook.detectAndRefresh(adapter: Any) {
    val now = System.currentTimeMillis()
    if (now - lastStampCheckMs < 500L) return
    lastStampCheckMs = now
    val stamp = GroupStore.groupsStamp()
    if (stamp < 0L) return
    if (lastStamp == Long.MIN_VALUE) {
        lastStamp = stamp
        return
    }
    if (stamp == lastStamp) return
    lastStamp = stamp
    synchronized(groupCache) { groupCache.clear(); cacheTs.clear() }
    if (pendingRefresh) return
    pendingRefresh = true
    mainHandler.post {
        pendingRefresh = false
        try {
            rvAdapterClass?.let { if (it.isInstance(adapter)) rvModels.remove(adapter) }
            invalidateLegacyModel(adapter)
            XposedHelpers.callMethod(adapter, "notifyDataSetChanged")
            log("分组已变更，已刷新会话列表")
        } catch (t: Throwable) {
            log("刷新会话列表失败: ${t.message}")
        }
    }
}

internal fun ConversationGroupHook.sortedGroups(): List<WxGroup> {
    val now = System.currentTimeMillis()
    synchronized(groupCache) {
        val cached = groupCache["*"]
        val ts = cacheTs["*"] ?: 0
        if (cached != null && now - ts < CACHE_TTL) return cached
    }
    val groups = GroupStore.getGroupsXposed(null)
    val ordered = GroupStore.sortForDisplay(groups)
    if (orderLogged < 3) {
        orderLogged++
        log(
            "分组顺序: " + ordered.joinToString(" | ") {
                "${it.name}(id=${it.id}, order=${it.order}, pinned=${it.pinned}, key=${GroupStore.orderKey(it)})"
            }
        )
    }
    synchronized(groupCache) {
        groupCache["*"] = ordered
        cacheTs["*"] = now
        if (groupCache.size > 8) {
            groupCache.clear()
            cacheTs.clear()
        }
    }
    // 排序规则：置顶优先 → 用户自定义顺序 → 创建时间 → 名字。
    // 实现在 GroupStore.sortForDisplay（模块 App 的列表也用它，保证两边顺序一致）。
    // 「用户自定义顺序」order 由长按分组 → 上移/下移/拖动排序写入；
    // 没排过（order=0）时回退到 id 里编码的创建时间，所以默认也是稳定且符合直觉的顺序。
    return ordered
}
/**
 * 让虚拟模型失效并**立刻重建**。
 *
 * 绝不能只把 legacyModel 置 null 就等着 notifyDataSetChanged 生效：
 * notify 是 post 到主线程稍后才跑的，而这期间 ListView 随时可能调
 * getCount()/getView()，那时模型为 null → getView 只能走兜底，
 * 历史版本「点一下就闪退」正是这个空窗。
 */
internal fun ConversationGroupHook.invalidateLegacyModel(a: Any?) {
    legacyModelKey = null
    if (a == null) return
    val rows = legacyRows(a)?.size ?: return
    if (rows <= 0) return
    val groups = sortedGroups()
    legacyModel = buildLegacyModel(a, rows, groups)
    legacyModelKey = modelKey(rows, groups)
}

/** 展开/折叠后让列表立即重绘（必须 notify，否则 ListView 不会重新读 getCount） */
internal fun ConversationGroupHook.notifyList() {
    val a = adapterRef?.get()
    if (a == null) {
        legacyModelKey = null
        return
    }
    rvAdapterClass?.let { if (it.isInstance(a)) rvModels.remove(a) }
    invalidateLegacyModel(a)
    mainHandler.post {
        try {
            XposedHelpers.callMethod(a, "notifyDataSetChanged")
        } catch (t: Throwable) {
            log("notifyDataSetChanged 失败: ${t.message}")
        }
    }
}
