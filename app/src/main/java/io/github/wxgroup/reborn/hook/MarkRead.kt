package io.github.wxgroup.reborn.hook

import android.view.View
import io.github.wxgroup.reborn.xp.XposedHelpers

/**
 * 「分组已读」—— 把某个分组里所有未读会话标为已读。
 *
 * ── 为什么不用自己改字段 ────────────────────────────────────────────
 * 未读数在微信里有两份：内存里的会话对象（`field_unReadCount`）和 rconversation 表。
 * 只改内存字段，角标当场会消失，但下一次列表刷新 / 冷启动就会从 DB 读回旧值，
 * 底部「微信」tab 的红点也不会消 —— 看起来就是「点了没用」。
 *
 * 所以走微信自己的 `ConversationStorage`（8.0.79 = `com.tencent.mm.storage.l4`）的
 * `b0(String)`：它就是把 unReadCount / unReadMuteCount / atCount / UnReadInvite
 * 四个列清零并写库，然后通知存储观察者。会话列表长按 →「标为已读」调用的就是它
 * （实测调用方：com.tencent.mm.ui.conversation.q3.onMMMenuItemSelected），
 * 所以行为和用户手动操作**完全一致**，顺带满足「其它端同步已读」。
 *
 * ── 实例怎么拿 ─────────────────────────────────────────────────────
 * l4 没有静态单例，只能从微信的 DI 容器取：
 *     gp0.j1.v(tn3.c4)  →  PluginMessengerFoundation 实现（h2）
 *     h2.ej()           →  ConversationStorage（l4）
 * 这两个混淆名是 8.0.78 / 8.0.79 的实际值。换版本若失效，用下面的方法重新确认：
 *   · 在 tinker dex 里找 const-string "MicroMsg.ConversationStorage"
 *   · 找调用 `b0(Ljava/lang/String;)Z` 的菜单处理类（conversation/q3 一类）
 * 失败时只打一条日志并静默降级（不影响列表其它功能）。
 */

private const val STORAGE_HOLDER_LEGACY = "com.tencent.mm.model.c"
private const val MARK_READ_METHOD = "b0"

/**
 * 微信 DI 定位器的候选名。
 *
 * ⚠️ 这里踩过一个坑：**运行时用的是 base.apk 的 PathClassLoader**，
 * 不是 tinker 补丁包的类加载器 —— 两边的混淆名并不一致：
 *      base.apk :  pq0.i1.v(mp3.c4)  →  h2.Bj()
 *      tinker   :  gp0.j1.v(tn3.c4)  →  h2.ej()
 * 所以不能只写一套，按候选逐个试。存储类本身和已读方法名（l4 / b0）两边一致。
 */
private val DI_RESOLVERS = listOf("pq0.i1", "gp0.j1")
private val FOUNDATION_SERVICES = listOf("mp3.c4", "tn3.c4")

/** 缓存解析结果（失败也缓存，避免每次点击都重试一整轮反射） */
private var storageResolveFailed = false

/**
 * 取微信 ConversationStorage 实例；拿不到返回 null。
 *
 * 解析顺序：
 *   1. 微信 DI：`<resolver>.v(<foundation service>)` → h2 → `Bj()` / `ej()`
 *   2. 老版本兜底：`com.tencent.mm.model.c.Yp()`（6.x/7.x 的写法）
 */
internal fun ConversationGroupHook.conversationStorage(): Any? {
    storageRef?.let { return it }
    if (storageResolveFailed) return null
    val loader = hookedLoader ?: return null

    var holder: Any? = null
    for (r in DI_RESOLVERS) {
        val rc = try {
            Class.forName(r, false, loader)
        } catch (t: Throwable) {
            continue
        }
        val v = findClassResolver(rc) ?: continue
        for (s in FOUNDATION_SERVICES) {
            val sc = try {
                Class.forName(s, false, loader)
            } catch (t: Throwable) {
                continue
            }
            holder = try {
                v.invoke(null, sc)
            } catch (t: Throwable) {
                null
            }
            if (holder != null) {
                log("ConversationStorage: $r.v($s) -> ${holder.javaClass.name}")
                break
            }
        }
        if (holder != null) break
    }

    var st = callStorageGetter(holder)
    if (st == null) {
        // 老版本（6.x/7.x）：ConversationStorage 挂在 model.c 上
        try {
            val legacy = Class.forName(STORAGE_HOLDER_LEGACY, false, loader)
            st = legacy.getDeclaredMethod("Yp").apply { isAccessible = true }.invoke(null)
        } catch (_: Throwable) {
        }
    }
    if (st == null) {
        storageResolveFailed = true
        log("ConversationStorage 解析失败：本次运行不再尝试，「已读」按钮将只提示不可用")
        return null
    }
    storageRef = st
    log("ConversationStorage 已就绪: ${st.javaClass.name}")
    return st
}

/** 在微信的 DI 定位器上找 `static v(Class)` */
private fun findClassResolver(rc: Class<*>): java.lang.reflect.Method? {
    val candidates = try {
        rc.methods.toList() + rc.declaredMethods.toList()
    } catch (t: Throwable) {
        return null
    }
    val m = candidates.firstOrNull {
        it.name == "v" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Class::class.java
    } ?: return null
    m.isAccessible = true
    return m
}

/**
 * holder 上的存储 getter：base 包里叫 `Bj()`，补丁包里叫 `ej()`，
 * 名字不靠谱 —— 按「无参 + 返回类型是 ConversationStorage(l4)」兜底找。
 */
private fun callStorageGetter(holder: Any?): Any? {
    if (holder == null) return null
    val methods = try {
        holder.javaClass.methods.toList()
    } catch (t: Throwable) {
        return null
    }
    val m = methods.firstOrNull {
        it.parameterTypes.isEmpty() && (it.name == "ej" || it.name == "Bj")
    } ?: methods.firstOrNull {
        it.parameterTypes.isEmpty() && it.returnType.name.endsWith(".l4")
    } ?: return null
    m.isAccessible = true
    return try {
        m.invoke(holder)
    } catch (t: Throwable) {
        null
    }
}

/**
 * 把单个会话标为已读（微信原生路径）。
 *
 * @param k4 会话对象，用于同步清掉内存里的未读字段 —— b0 只写 DB 和通知，
 *           内存对象要等微信自己重回列表才更新；清零后当前这一帧的角标就是对的。
 */
internal fun ConversationGroupHook.markTalkerRead(talker: String, k4: Any? = null): Boolean {
    val st = conversationStorage() ?: return false
    return try {
        val ok = XposedHelpers.callMethod(st, MARK_READ_METHOD, talker) as? Boolean ?: false
        if (ok && k4 != null) {
            // 两个列都清：群消息免打扰的会话未读记在 unReadMuteCount 上
            XposedHelpers.setObjectField(k4, "field_unReadCount", 0)
            XposedHelpers.setObjectField(k4, "field_unReadMuteCount", 0)
        }
        ok
    } catch (t: Throwable) {
        log("标记已读失败($talker): ${t.javaClass.simpleName}: ${t.message}")
        false
    }
}

/** 把整个分组标为已读 */
internal fun ConversationGroupHook.markGroupRead(gid: String) {
    val g = sortedGroups().firstOrNull { it.id == gid } ?: return
    if (g.members.isEmpty()) {
        toast(appContext, "「${g.name}」还没有成员")
        return
    }
    if (conversationStorage() == null) {
        toast(appContext, "当前微信版本不支持直接标记已读")
        return
    }
    val a = adapterRef?.get() ?: return
    val rows = legacyRows(a) ?: return

    var done = 0
    var failed = 0
    for (row in rows) {
        val k4 = XposedHelpers.getObjectField(row, "d") ?: continue
        val u = talkerOf(k4) ?: continue
        if (u !in g.members) continue
        if (unreadOf(k4) <= 0) continue
        if (markTalkerRead(u, k4)) done++ else failed++
    }
    if (done == 0) {
        // 两个入口可能各触发一次（performItemClick + 监听器包装），第二次到这里时
        // 未读已经是 0 了 —— 静默返回，不要弹「没有未读」打扰用户。
        if (failed > 0) toast(appContext, "标记已读失败，请重试")
        return
    }
    // 未读快照立刻失效：否则组行还会拿旧快照画角标
    unreadSnapshotAt = 0L
    notifyList()
    toast(
        appContext,
        buildString {
            append("已把「${g.name}」$done 个会话标为已读")
            if (failed > 0) append("，$failed 个失败")
        }
    )
    log("分组已读: ${g.name} 成功=$done 失败=$failed")
}

/**
 * 这次点击是不是落在「已读」按钮上。
 *
 * 行内按钮不挂监听（会被吃掉 DOWN、阻断滚动），统一用按下坐标命中：
 * `getLocationOnScreen` 和实际绘制同一套变换，判定必然和眼睛看到的一致。
 * 热区上下左右各外扩 6dp，小胶囊也好点。
 */
internal fun ConversationGroupHook.hitGroupReadButton(row: View?): Boolean {
    val holder = row?.tag as? FolderHolder ?: return false
    val btn = holder.readBtn
    if (btn.visibility != View.VISIBLE || btn.width <= 0 || btn.height <= 0) return false
    if (!probeFresh()) return false
    val loc = IntArray(2)
    btn.getLocationOnScreen(loc)
    val pad = dp(btn.context, 6)
    return downRawX >= loc[0] - pad &&
        downRawX <= loc[0] + btn.width + pad &&
        downRawY >= loc[1] - pad &&
        downRawY <= loc[1] + btn.height + pad
}
