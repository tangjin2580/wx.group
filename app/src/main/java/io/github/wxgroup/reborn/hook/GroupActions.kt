package io.github.wxgroup.reborn.hook

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.core.WxSettings
import io.github.wxgroup.reborn.xp.XposedHelpers

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
    // 排序模式下长按不弹菜单（此时的按压/移动是在拖动行）。
    // 放在函数体里而不是只挡某个入口：长按有 performLongPress 和
    // OnItemLongClickListener 包装两条路径，任何一条漏挡都会在拖动时弹菜单。
    if (reorderMode) return true
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
    val items = listOf(
        "添加成员…",
        "拖动排序…",
        "重命名",
        "换图标",
        "改颜色",
        "清空成员",
        "删除分组"
    )
    try {
        // 微信风格底部动作面板（系统 AlertDialog 的字体/圆角/行高和微信不一致，一眼出戏）
        showWxActionSheet(
            act = act,
            title = "分组「${group.name}」",
            items = items,
            destructiveIndex = items.lastIndex,
            onPick = { which ->
                try {
                    when (which) {
                        0 -> showBatchMembers(act, group.id)
                        // 直接在微信会话列表里拖动排序，不再跳模块 App
                        1 -> enterReorderMode()
                        2 -> showRenameGroupDialog(act, group.id)
                        3 -> showIconPicker(act, group.id)
                        4 -> showColorPicker(act, group.id)
                        5 -> confirmClearMembers(act, group.id)
                        6 -> confirmDeleteGroup(act, group.id)
                    }
                } catch (t: Throwable) {
                    log("分组操作失败: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        )
    } catch (t: Throwable) {
        log("打开分组操作菜单失败: ${t.javaClass.simpleName}: ${t.message}")
    }
    return true
}

/**
 * 取一份「可安全修改」的分组副本。
 *
 * `sortedGroups()` 返回的是缓存里的对象 —— 直接在它的 members/name 上改，
 * 一旦 persistGroups 抛异常或还没写盘，缓存就已经是脏的（之后所有渲染都用脏数据）。
 * 所有改分组的动作都先 copy 一份再改。
 */
internal fun ConversationGroupHook.mutableGroups(): MutableList<WxGroup> =
    sortedGroups().mapTo(ArrayList()) { it.copy(members = it.members.toMutableSet()) }

/** 把分组在列表里挪一格 → 已被「拖动排序」页取代（长按分组 → 拖动排序），函数移除 */

internal fun ConversationGroupHook.normalizeOrder(all: List<WxGroup>) {
    all.forEachIndexed { i, g -> g.order = i + 1 }
}

/**
 * 批量成员管理（分组行长按 → 「添加成员…」）。
 *
 * 列出当前会话列表里的全部会话（显示名 + 未读标记），勾选状态 = 当前成员；
 * 点「保存」后按勾选结果**整表同步**：新勾的加入（同时从其它组摘掉，保持单组语义），
 * 取消勾的移出。一次保存 = 批量加人 + 批量移人，不用再逐个长按会话。
 */
internal fun ConversationGroupHook.showBatchMembers(act: android.app.Activity, gid: String) {
    val a = adapterRef?.get()
    val rows = a?.let { legacyRows(it) }
    if (a == null || rows == null) {
        toast(act, "拿不到会话列表，请回到微信会话页再试")
        return
    }
    // 会话列表当前顺序就是用户熟悉的新旧顺序，直接沿用
    data class Conv(val talker: String, val label: String, val unread: Int)
    val convs = ArrayList<Conv>(rows.size)
    val seen = HashSet<String>()
    for (row in rows) {
        try {
            val k4 = XposedHelpers.getObjectField(row, "d") ?: continue
            val u = talkerOf(k4) ?: continue
            if (!seen.add(u)) continue
            val name = displayNameOf(k4) ?: u
            val unread = unreadOf(k4)
            convs.add(Conv(u, if (unread > 0) "$name（$unread 条未读）" else name, unread))
        } catch (_: Throwable) {
        }
    }
    if (convs.isEmpty()) {
        toast(act, "会话列表为空")
        return
    }
    val g = sortedGroups().firstOrNull { it.id == gid } ?: return
    val checked = BooleanArray(convs.size) { convs[it].talker in g.members }
    val labels = convs.map { c ->
        // 让用户知道这个会话现在收在哪个组，避免「加了没反应」的困惑
        val owner = sortedGroups().firstOrNull { c.talker in it.members && it.id != gid }?.name
        if (owner != null) "${c.label} · 在「$owner」" else c.label
    }
    try {
        android.app.AlertDialog.Builder(act)
            .setTitle("管理「${g.name}」的成员（${g.members.size}）")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, _, _ -> }
            .setPositiveButton("保存") { _, _ ->
                val all = mutableGroups()
                val target = all.firstOrNull { it.id == gid } ?: return@setPositiveButton
                var added = 0
                var removed = 0
                for (i in convs.indices) {
                    val u = convs[i].talker
                    val was = u in target.members
                    val now = checked[i]
                    if (!was && now) {
                        for (other in all) other.members.remove(u)  // 单组语义
                        target.members.add(u)
                        added++
                    } else if (was && !now) {
                        target.members.remove(u)
                        removed++
                    }
                }
                if (added == 0 && removed == 0) {
                    toast(act, "没有改动")
                    return@setPositiveButton
                }
                persistGroups(all)
                toast(act, buildString {
                    if (added > 0) append("已加入 $added 个")
                    if (added > 0 && removed > 0) append("、")
                    if (removed > 0) append("已移出 $removed 个")
                })
            }
            .setNegativeButton("取消", null)
            .show()
    } catch (t: Throwable) {
        log("批量成员对话框失败: ${t.javaClass.simpleName}: ${t.message}")
    }
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
            val all = mutableGroups()
            all.firstOrNull { it.id == gid }?.name = name
            persistGroups(all)
            toast(act, "已重命名为「$name」")
        }
        .setNegativeButton("取消", null)
        .show()
}

/**
 * 换图标：两栏切换 —— 单色符号（沉浸，随主题着色）/ 彩色 emoji。
 *
 * 默认停在分组当前图标所属的那栏，用户不用先猜「我上次选的是哪种」。
 * 单色符号在网格里就按本组强调色预览，所见即所得。
 */
internal fun ConversationGroupHook.showIconPicker(act: android.app.Activity, gid: String) {
    val g = sortedGroups().firstOrNull { it.id == gid } ?: return
    val accent = GroupTheme.colorForGroup(
        gid, sortedGroups().indexOfFirst { it.id == gid }, wxSettings()
    )
    var mode = if (GroupTheme.isGlyph(g.icon)) 0 else 1

    val root = android.widget.LinearLayout(act).apply {
        orientation = android.widget.LinearLayout.VERTICAL
    }
    val tabs = android.widget.LinearLayout(act).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        setPadding(dp(act, 16), dp(act, 8), dp(act, 16), 0)
    }
    val grid = android.widget.GridView(act).apply { numColumns = 6 }

    val adapter = object : android.widget.BaseAdapter() {
        var data: List<String> = GroupTheme.iconsFor(mode)
        override fun getCount() = data.size
        override fun getItem(position: Int) = data[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val tv = (cv as? android.widget.TextView) ?: android.widget.TextView(act).apply {
                textSize = 22f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, dp(act, 12), 0, dp(act, 12))
            }
            val icon = data[position]
            tv.text = icon
            // 单色符号按本组强调色预览；emoji 用不着（字体自带颜色），给回默认文字色
            tv.setTextColor(if (GroupTheme.isGlyph(icon)) accent else if (isDark(act)) Color.WHITE else Color.BLACK)
            return tv
        }
    }
    grid.adapter = adapter

    // 先声明成可变量：Button 的点击回调要调用 refreshTabs，而它又依赖 tabBtns，
    // 用 var 持有闭包把「互相引用」的先后顺序解开（局部函数不能前向引用，这样写最稳）。
    var refreshTabs: () -> Unit = {}
    val tabBtns = listOf("单色符号", "彩色 emoji").mapIndexed { index, label ->
        android.widget.Button(act).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minHeight = dp(act, 40)
            setOnClickListener {
                mode = index
                adapter.data = GroupTheme.iconsFor(mode)
                adapter.notifyDataSetChanged()
                refreshTabs()
            }
        }.also { tabs.addView(it, android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { if (index == 0) rightMargin = dp(act, 8) }) }
    }

    refreshTabs = {
        tabBtns.forEachIndexed { i, b ->
            val on = i == mode
            // 选中 = 强调色实心底，未选中 = 透明底 + 默认文字色（微信里不引入额外样式）
            b.setBackgroundColor(if (on) accent else Color.TRANSPARENT)
            b.setTextColor(if (on) Color.WHITE else if (isDark(act)) Color.WHITE else Color.BLACK)
            b.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }
    refreshTabs()
    root.addView(tabs)
    root.addView(grid, android.widget.LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 340)
    ))

    val dlg = android.app.AlertDialog.Builder(act)
        .setTitle("选择图标（${g.name}）")
        .setView(root)
        .setNegativeButton("取消", null)
        .create()
    grid.setOnItemClickListener { _, _, pos, _ ->
        val all = mutableGroups()
        all.firstOrNull { it.id == gid }?.icon = adapter.getItem(pos)
        persistGroups(all)
        toast(act, "已更换图标")
        dlg.dismiss()
    }
    dlg.show()
}

/**
 * 改单个分组的颜色（微信内菜单）。
 *
 * 候选色来自**当前主题的色板**，保证怎么选都在一个色系里；末尾是主题主色。
 * 写入 [WxSettings.groupColors] —— 分组数据（成员/顺序）一个字节都不动，
 * 只改「外观配置」，走的是设置镜像那条通道（模块 App 下次读设置时会以镜像为准）。
 */
internal fun ConversationGroupHook.showColorPicker(act: android.app.Activity, gid: String) {
    val s = wxSettings()
    val g = sortedGroups().firstOrNull { it.id == gid } ?: return
    val theme = GroupTheme.of(s.themeId)
    val candidates = (theme.swatches + theme.palette(themeDark(act, s)).primary).distinct()
    val current = s.groupColors[gid]

    val grid = android.widget.GridView(act).apply {
        numColumns = 5
        adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = candidates.size
            override fun getItem(position: Int) = candidates[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val c = candidates[position]
                val box = android.widget.FrameLayout(act).apply {
                    val p = dp(act, 8)
                    setPadding(p, p, p, p)
                }
                val dot = android.view.View(act).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(c)
                        if (c == current) setStroke(dp(act, 2), if (isDark(act)) Color.WHITE else Color.BLACK)
                    }
                    layoutParams = android.widget.FrameLayout.LayoutParams(dp(act, 34), dp(act, 34), Gravity.CENTER)
                }
                box.addView(dot)
                return box
            }
        }
    }
    val dlg = android.app.AlertDialog.Builder(act)
        .setTitle("「${g.name}」的颜色")
        .setView(grid)
        .setNeutralButton("跟随主题") { _, _ ->
            val map = LinkedHashMap(s.groupColors)
            map.remove(gid)
            saveThemeSettings(s.copy(groupColors = map))
            toast(act, "已恢复跟随主题")
        }
        .setNegativeButton("取消", null)
        .create()
    grid.setOnItemClickListener { _, _, pos, _ ->
        val c = candidates[pos]
        val map = LinkedHashMap(s.groupColors)
        map[gid] = c
        saveThemeSettings(s.copy(groupColors = map))
        toast(act, "已设置颜色")
        dlg.dismiss()
    }
    dlg.show()
}

/**
 * 在微信进程里保存「主题/外观」设置。
 * 只写 /data/local/tmp 的设置镜像（666，微信 uid 可写），模块 App 下次读设置时会认它；
 * 顺手让两层缓存失效，列表下一次 bind 就用新颜色，不等 2s TTL。
 */
internal fun ConversationGroupHook.saveThemeSettings(s: io.github.wxgroup.reborn.core.WxSettings) {
    try {
        io.github.wxgroup.reborn.core.GroupStore.saveSettingsXposed(s)
    } catch (t: Throwable) {
        log("保存主题设置失败: ${t.message}")
    }
    wxSettingsCache = null
    wxSettingsAt = 0L
    notifyList()
}

internal fun ConversationGroupHook.confirmClearMembers(act: android.app.Activity, gid: String) {
    val g = sortedGroups().firstOrNull { it.id == gid } ?: return
    android.app.AlertDialog.Builder(act)
        .setTitle("清空成员")
        .setMessage("把「${g.name}」里的 ${g.members.size} 个会话放回主列表？分组本身保留。")
        .setPositiveButton("清空") { _, _ ->
            val all = mutableGroups()
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

/**
 * 「添加到分组」：列出全部分组让用户挑，**顶部带搜索框**（分组多了以后纯列表不好找），
 * 末尾附「新建分组」。
 */
internal fun ConversationGroupHook.showGroupChooser(act: android.app.Activity, talker: String?) {
    if (talker == null) {
        showNewGroupDialog(act, null)
        return
    }
    val groups = sortedGroups()
    val ctx = act

    fun labelOf(g: WxGroup): String = if (talker in g.members) "${g.name}（已包含）" else g.name

    // 自建视图：搜索框 + 列表。simple_list_item_1 够用，还能让搜索过滤实时生效
    val root = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
    val input = android.widget.EditText(ctx).apply {
        hint = "搜索分组…"
        setSingleLine(true)
        setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4))
        setTextColor(if (isDark(ctx)) Color.WHITE else Color.BLACK)
        setHintTextColor(if (isDark(ctx)) Color.parseColor("#8a8a8e") else Color.parseColor("#999999"))
    }
    val list = android.widget.ListView(ctx)
    root.addView(input, android.widget.LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(list, android.widget.LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 320)))

    fun pick(g: WxGroup) {
        try { addTalkerToGroup(g.id, talker) } catch (t: Throwable) { log("选择分组失败: ${t.message}") }
    }

    val adapter = object : android.widget.BaseAdapter() {
        private var shown: List<Any> = emptyList()   // WxGroup 或 "NEW" 标记
        private var query = ""
        init { refilter("") }
        fun refilter(q: String) {
            query = q.trim()
            val filtered: List<Any> = if (query.isEmpty()) {
                groups + "NEW"
            } else {
                // 名称或成员 username 命中都算（群名不直观时可以直接搜 wxid/群号）
                val hit = groups.filter { g ->
                    g.name.contains(query, true) || g.members.any { it.contains(query, true) }
                }
                hit + "NEW"
            }
            shown = filtered
            notifyDataSetChanged()
        }
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val tv = (convertView as? android.widget.TextView) ?: android.widget.TextView(ctx).apply {
                textSize = 16f
                setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12))
                setTextColor(if (isDark(ctx)) Color.WHITE else Color.BLACK)
            }
            tv.text = when (val it = shown[position]) {
                "NEW" -> "＋ 新建分组…"
                is WxGroup -> labelOf(it)
                else -> it.toString()
            }
            return tv
        }
    }
    list.adapter = adapter
    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = adapter.refilter(s?.toString() ?: "")
    })
    val dlg = android.app.AlertDialog.Builder(act)
        .setTitle("添加到分组")
        .setView(root)
        .setNegativeButton("取消", null)
        .create()
    list.setOnItemClickListener { _, _, pos, _ ->
        when (val it = adapter.getItem(pos)) {
            "NEW" -> { dlg.dismiss(); showNewGroupDialog(act, talker) }
            is WxGroup -> { dlg.dismiss(); pick(it) }
        }
    }
    dlg.show()
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
    val all = mutableGroups()
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
    val all = mutableGroups()
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
    val all = mutableGroups()
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
