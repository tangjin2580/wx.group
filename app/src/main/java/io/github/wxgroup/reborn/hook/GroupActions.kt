package io.github.wxgroup.reborn.hook

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.core.WxGroup

/**
 * 分组操作：增删改、排序、图标、以及菜单动作落地。
 *
 * 全部在**微信界面内**用对话框完成（和微信「标签」的交互一致），
 * 只有「拖动排序」会打开模块自己的排序页（微信的标签排序也是独立页）。
 */

/**
 * 收纳组行被长按：在**微信界面内**弹分组操作菜单（和微信「标签」的交互一致：
 * 排序/重命名/删除都在自己的列表里完成，不跳出去）。
 * 「拖动排序…」会打开模块的排序页（微信的标签排序也是一个独立的排序页）。
 */
internal fun ConversationGroupHook.handleHeaderLongPress(view: View?, group: WxGroup): Boolean {
    val now = android.os.SystemClock.uptimeMillis()
    if (lastLongGid == group.id && now - lastLongAt < 500L) return true
    lastLongGid = group.id
    lastLongAt = now
    try {
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    } catch (_: Throwable) {
    }
    // 弹对话框必须要 Activity 当 Context（用 Application 会 BadTokenException）
    val act = activityFromContext(view?.context)
        ?: pendingActivity?.get()
        ?: run {
            log("收纳组长按: 拿不到 Activity，回退到模块界面")
            (view?.context ?: appContext)?.let { launchGroupManager(it, group.id) }
            return true
        }
    log("收纳组长按: 打开分组操作菜单「${group.name}」")
    val idx = sortedGroups().indexOfFirst { it.id == group.id }
    val total = sortedGroups().size
    val items = arrayOf(
        if (idx <= 0) "上移（已在最前）" else "上移",
        if (idx < 0 || idx >= total - 1) "下移（已在最后）" else "下移",
        "拖动排序…",
        "重命名",
        "换图标",
        "清空成员",
        "删除分组"
    )
    try {
        android.app.AlertDialog.Builder(act)
            .setTitle("分组「${group.name}」")
            .setItems(items) { _, which ->
                try {
                    when (which) {
                        0 -> moveGroup(group.id, -1)
                        1 -> moveGroup(group.id, 1)
                        2 -> launchGroupSorter(act)
                        3 -> showRenameGroupDialog(act, group.id)
                        4 -> showIconPicker(act, group.id)
                        5 -> confirmClearMembers(act, group.id)
                        6 -> confirmDeleteGroup(act, group.id)
                    }
                } catch (t: Throwable) {
                    log("分组操作失败: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    } catch (t: Throwable) {
        log("打开分组操作菜单失败: ${t.javaClass.simpleName}: ${t.message}")
    }
    return true
}

/** 把分组在列表里挪一格。挪完给所有分组写上显式 order，从此顺序完全听用户的。 */
internal fun ConversationGroupHook.moveGroup(gid: String, delta: Int) {
    val all = sortedGroups().toMutableList()
    val i = all.indexOfFirst { it.id == gid }
    if (i < 0) return
    val j = i + delta
    val act = pendingActivity?.get() ?: appContext
    if (j < 0 || j >= all.size) {
        toast(act, if (delta < 0) "已经在最前面了" else "已经在最后面了")
        return
    }
    val g = all.removeAt(i)
    all.add(j, g)
    normalizeOrder(all)
    persistGroups(all)
    toast(act, "已${if (delta < 0) "上移" else "下移"}「${g.name}」")
}

internal fun ConversationGroupHook.normalizeOrder(all: List<WxGroup>) {
    all.forEachIndexed { i, g -> g.order = i + 1 }
}

internal fun ConversationGroupHook.showRenameGroupDialog(act: android.app.Activity, gid: String) {
    val g = sortedGroups().firstOrNull { it.id == gid } ?: return
    val input = android.widget.EditText(act).apply {
        hint = "分组名称"
        setText(g.name)
        setSelection(text.length)
        setTextColor(if (isDark(act)) Color.WHITE else Color.BLACK)
    }
    val wrap = android.widget.FrameLayout(act).apply {
        val pad = dp(act, 20)
        setPadding(pad, dp(act, 8), pad, 0)
        addView(input)
    }
    android.app.AlertDialog.Builder(act)
        .setTitle("重命名分组")
        .setView(wrap)
        .setPositiveButton("保存") { _, _ ->
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                toast(act, "分组名不能为空")
                return@setPositiveButton
            }
            val all = sortedGroups()
            all.firstOrNull { it.id == gid }?.name = name
            persistGroups(all)
            toast(act, "已重命名为「$name」")
        }
        .setNegativeButton("取消", null)
        .show()
}

/** 换图标：从候选 emoji 里挑一个，点选即保存 */
internal fun ConversationGroupHook.showIconPicker(act: android.app.Activity, gid: String) {
    val icons = GroupTheme.ICONS
    val grid = android.widget.GridView(act).apply {
        numColumns = 5
        adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = icons.size
            override fun getItem(position: Int) = icons[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val tv: android.widget.TextView = (convertView as? android.widget.TextView)
                    ?: android.widget.TextView(act).apply {
                        textSize = 24f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(act, 10), 0, dp(act, 10))
                    }
                tv.text = icons[position]
                return tv
            }
        }
    }
    val dlg = android.app.AlertDialog.Builder(act)
        .setTitle("选择图标")
        .setView(grid)
        .setNegativeButton("取消", null)
        .create()
    grid.setOnItemClickListener { _, _, pos, _ ->
        val all = sortedGroups()
        all.firstOrNull { it.id == gid }?.icon = icons[pos]
        persistGroups(all)
        toast(act, "已更换图标")
        dlg.dismiss()
    }
    dlg.show()
}

internal fun ConversationGroupHook.confirmClearMembers(act: android.app.Activity, gid: String) {
    val g = sortedGroups().firstOrNull { it.id == gid } ?: return
    android.app.AlertDialog.Builder(act)
        .setTitle("清空成员")
        .setMessage("把「${g.name}」里的 ${g.members.size} 个会话放回主列表？分组本身保留。")
        .setPositiveButton("清空") { _, _ ->
            val all = sortedGroups()
            all.firstOrNull { it.id == gid }?.members?.clear()
            persistGroups(all)
            toast(act, "已清空「${g.name}」")
        }
        .setNegativeButton("取消", null)
        .show()
}

internal fun ConversationGroupHook.confirmDeleteGroup(act: android.app.Activity, gid: String) {
    val g = sortedGroups().firstOrNull { it.id == gid } ?: return
    android.app.AlertDialog.Builder(act)
        .setTitle("删除分组")
        .setMessage("删除「${g.name}」？里面的 ${g.members.size} 个会话会回到主列表，聊天记录不受影响。")
        .setPositiveButton("删除") { _, _ ->
            val all = sortedGroups().filterNot { it.id == gid }
            persistGroups(all)
            if (openGroupId == gid) closeGroupPage()
            toast(act, "已删除「${g.name}」")
        }
        .setNegativeButton("取消", null)
        .show()
}
internal fun ConversationGroupHook.onMenuAddToGroup() = onMenuAction { act, talker -> showGroupChooser(act, talker) }

internal fun ConversationGroupHook.onMenuNewGroup() = onMenuAction { act, talker -> showNewGroupDialog(act, talker) }

internal fun ConversationGroupHook.onMenuRemoveFromGroup() = onMenuAction { _, talker ->
    if (talker.isNullOrBlank()) return@onMenuAction
    removeTalkerFromGroup(talker)
}

internal fun ConversationGroupHook.onMenuAction(block: (android.app.Activity, String?) -> Unit) {
    val talker = pendingTalker
    val act = pendingActivity?.get()
    if (act == null) {
        log("菜单动作缺少 Activity，回退到模块界面")
        if (talker != null) launchGroupPicker(appContext ?: return, talker)
        return
    }
    try {
        block(act, talker)
    } catch (t: Throwable) {
        log("菜单动作失败: ${t.javaClass.simpleName}: ${t.message}")
        talker?.let { launchGroupPicker(act, it) }
    }
}

/** 「添加到分组」：列出全部分组让用户挑（已包含的标注出来），末尾附「新建分组」 */
internal fun ConversationGroupHook.showGroupChooser(act: android.app.Activity, talker: String?) {
    if (talker == null) {
        showNewGroupDialog(act, null)
        return
    }
    val groups = sortedGroups()
    val labels = ArrayList<String>(groups.size + 1)
    for (g in groups) labels.add(if (talker in g.members) "${g.name}（已包含）" else g.name)
    labels.add("＋ 新建分组…")
    android.app.AlertDialog.Builder(act)
        .setTitle("添加到分组")
        .setItems(labels.toTypedArray()) { _, which ->
            try {
                if (which >= groups.size) showNewGroupDialog(act, talker)
                else addTalkerToGroup(groups[which].id, talker)
            } catch (t: Throwable) {
                log("选择分组失败: ${t.message}")
            }
        }
        .setNegativeButton("取消", null)
        .show()
}

/** 「新建分组」：输入名称，建完直接把当前会话放进去 */
internal fun ConversationGroupHook.showNewGroupDialog(act: android.app.Activity, talker: String?) {
    val input = android.widget.EditText(act).apply {
        hint = "分组名称"
        setTextColor(if (isDark(act)) Color.WHITE else Color.BLACK)
    }
    val wrap = android.widget.FrameLayout(act).apply {
        val pad = dp(act, 20)
        setPadding(pad, dp(act, 8), pad, 0)
        addView(input)
    }
    android.app.AlertDialog.Builder(act)
        .setTitle("新建分组")
        .setView(wrap)
        .setPositiveButton("创建") { _, _ -> createGroup(input.text?.toString().orEmpty(), talker) }
        .setNegativeButton("取消", null)
        .show()
}

internal fun ConversationGroupHook.addTalkerToGroup(gid: String, talker: String) {
    val all = sortedGroups()
    val g = all.firstOrNull { it.id == gid } ?: return
    // 收纳组是「文件夹」语义：一个会话只能待在一个分组里，
    // 所以加入新分组时把它从其它分组里摘掉（和微信折叠群聊一致）。
    var movedFrom: String? = null
    for (other in all) {
        if (other.id != gid && other.members.remove(talker)) movedFrom = other.name
    }
    if (talker in g.members) {
        if (movedFrom == null) {
            toast(pendingActivity?.get() ?: appContext, "已在「${g.name}」中")
            return
        }
    } else {
        g.members.add(talker)
    }
    persistGroups(all)
    val act = pendingActivity?.get() ?: appContext
    toast(act, if (movedFrom == null) "已加入「${g.name}」" else "已从「$movedFrom」移到「${g.name}」")
}

/** 「移出分组」：把会话从它所属的分组里摘掉，会话回到主列表 */
internal fun ConversationGroupHook.removeTalkerFromGroup(talker: String) {
    val all = sortedGroups()
    val names = ArrayList<String>()
    for (g in all) if (g.members.remove(talker)) names.add(g.name)
    if (names.isEmpty()) {
        toast(pendingActivity?.get() ?: appContext, "该会话不在任何分组里")
        return
    }
    persistGroups(all)
    toast(pendingActivity?.get() ?: appContext, "已移出「${names.joinToString("、")}」")
}

internal fun ConversationGroupHook.createGroup(rawName: String, talker: String?) {
    val name = rawName.trim()
    if (name.isBlank()) {
        toast(pendingActivity?.get() ?: appContext, "分组名不能为空")
        return
    }
    val all = sortedGroups().toMutableList()
    val existing = all.firstOrNull { it.name == name }
    if (existing != null) {
        if (talker != null) addTalkerToGroup(existing.id, talker) else toast(pendingActivity?.get(), "「$name」已存在")
        return
    }
    // 新分组排在最后：显式给一个比现有的都大的 order，
    // 否则 order=0 会排到前面去（用户手动排过顺序时尤其明显）。
    val nextOrder = (all.maxOfOrNull { it.order } ?: 0) + 1
    val g = WxGroup(id = "grp_" + System.currentTimeMillis(), name = name, order = nextOrder)
    if (!talker.isNullOrBlank()) {
        g.members.add(talker)
        // 同上：一个会话只属于一个分组，新建分组收走之后要从旧分组摘掉
        for (other in all) other.members.remove(talker)
    }
    // 如果之前从没手动排过（全是 0），顺手把现有分组也编号，保证新分组一定在最后
    if (all.any { it.order == 0 }) normalizeOrder(all)
    all.add(g)
    persistGroups(all)
    toast(pendingActivity?.get() ?: appContext, if (talker == null) "已新建「$name」" else "已新建「$name」并加入")
}
