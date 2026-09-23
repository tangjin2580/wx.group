package io.github.wxgroup.reborn.hook

import android.content.Context
import android.content.Intent
import android.view.View
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.util.ReflectionUtil
import io.github.wxgroup.reborn.xp.XC_MethodHook.MethodHookParam
import io.github.wxgroup.reborn.xp.XposedHelpers

/**
 * 宿主环境：从微信的长按回调 / View 里刨出 Activity、会话 username，
 * 以及「跳到模块自己的页面」的 Intent 构造。
 *
 * ⚠️ 跳模块 Activity 必须用 setClassName 显式带上模块包名（见 moduleIntent 的注释），
 * 用 `Intent(context, XxxActivity::class.java)` 会拼成 com.tencent.mm/... →
 * ActivityNotFoundException → 异常冲垮微信主线程 → 「点一下就闪退」。
 */
private const val MODULE_GROUP_MANAGE = "io.github.wxgroup.reborn.ui.GroupManageActivity"
private const val MODULE_SORT_GROUPS = "io.github.wxgroup.reborn.ui.SortGroupsActivity"
private const val MODULE_ACTION_ADD_TO_GROUP = "io.github.wxgroup.reborn.action.ADD_TO_GROUP"
private const val MODULE_ACTION_SORT_GROUPS = "io.github.wxgroup.reborn.action.SORT_GROUPS"
private const val MODULE_EXTRA_USERNAME = "username"
private const val MODULE_EXTRA_GROUP_ID = "group_id"

/** 从长按回调里找 Activity（对话框必须有 Activity 作 context，否则 BadTokenException） */
internal fun ConversationGroupHook.findActivity(param: MethodHookParam, view: View?): android.app.Activity? {
    (view?.context as? android.app.Activity)?.let { return it }
    for (f in listOf("f", "e", "d", "b")) {
        val v = try {
            XposedHelpers.getObjectField(param.thisObject, f)
        } catch (t: Throwable) {
            null
        }
        if (v is android.app.Activity) return v
    }
    var c: Context? = (param.thisObject as? Context)
        ?: (param.args.firstOrNull { it is Context } as? Context)
        ?: appContext
    var guard = 0
    while (c is android.content.ContextWrapper && guard++ < 10) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * 从一个（可能是 ContextWrapper 包装过的）Context 里刨出 Activity。
 * 弹对话框必须用 Activity 当 Context，用 Application 会崩（BadTokenException）。
 */
internal fun ConversationGroupHook.activityFromContext(ctx: Context?): android.app.Activity? {
    var c: Context? = ctx
    var guard = 0
    while (c is android.content.ContextWrapper && guard++ < 10) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

internal fun ConversationGroupHook.launchGroupPicker(context: Context, username: String) {
    try {
        context.startActivity(moduleIntent(MODULE_ACTION_ADD_TO_GROUP).apply {
            putExtra(MODULE_EXTRA_USERNAME, username)
        })
    } catch (t: Throwable) {
        // 这里绝不能把异常抛回微信的主线程 —— 一抛就是整个微信「闪退」。
        log("打开「添加到分组」失败: ${t.javaClass.name}: ${t.message}")
    }
}

internal fun ConversationGroupHook.launchGroupSorter(context: Context) {
    try {
        context.startActivity(moduleIntent(MODULE_ACTION_SORT_GROUPS, MODULE_SORT_GROUPS))
    } catch (t: Throwable) {
        log("打开分组排序失败: ${t.javaClass.name}: ${t.message}")
    }
}

internal fun ConversationGroupHook.launchGroupManager(context: Context, groupId: String) {
    try {
        context.startActivity(moduleIntent(null).apply {
            putExtra(MODULE_EXTRA_GROUP_ID, groupId)
        })
    } catch (t: Throwable) {
        log("打开分组管理失败: ${t.javaClass.name}: ${t.message}")
    }
}

/**
 * 构造指向**模块自己**那一个 Activity 的 Intent。
 *
 * ⚠️ 绝对不能用 `Intent(context, GroupManageActivity::class.java)`：
 * 我们跑在微信进程里，`context` 的包名是 com.tencent.mm，于是拼出来的是
 *     com.tencent.mm/io.github.wxgroup.reborn.ui.GroupManageActivity
 * 这个 Activity 当然不存在 → startActivity 抛 ActivityNotFoundException →
 * 未捕获异常顺着触摸回调冲垮微信主线程 → 用户看到的就是「点/长按一下就闪退」。
 * 必须用 setClassName 显式指定模块包名。
 */
internal fun ConversationGroupHook.moduleIntent(action: String?, activity: String = MODULE_GROUP_MANAGE): Intent {
    val i = Intent()
    i.setClassName(GroupStore.MODULE_PKG, activity)
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (action != null) i.action = action
    return i
}

internal fun ConversationGroupHook.extractUsername(view: View): String? = try {
    val tag = view.tag
    when (tag) {
        is String -> if (looksLikeUsername(tag)) tag else null
        else -> ReflectionUtil.getFieldValue(view, listOf("talker", "username")) as? String
    }
} catch (t: Throwable) {
    null
}

internal fun ConversationGroupHook.scanUsername(obj: Any?): String? {
    if (obj == null) return null
    for (f in listOf("g", "talker", "username", "field_username", "contact")) {
        val v = ReflectionUtil.getFieldValue(obj, listOf(f)) as? String
        if (!v.isNullOrBlank() && looksLikeUsername(v)) return v
    }
    return null
}

internal fun ConversationGroupHook.looksLikeUsername(v: String): Boolean =
    v.startsWith("wxid_") || v.endsWith("@chatroom") || v.endsWith("@im.chat") ||
        (v.contains("@") && !v.contains(" ") && v.length in 5..40)
