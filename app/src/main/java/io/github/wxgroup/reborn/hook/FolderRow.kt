package io.github.wxgroup.reborn.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.core.WxGroup

/**
 * 收纳组行的 View：构建 + 绑定。
 *
 * ⚠️ 行保持「不可点」（isClickable=false）：一旦 clickable 就会吃掉触摸 DOWN，
 * 从这一行起手无法滚动列表，而且会和微信自己的列表项监听器抢这次点击。
 * 点击/长按统一交给 ItemIntercept 那两处 framework 级拦截。
 */

// ==================================================================================
// 分组行 View
// ==================================================================================
internal fun ConversationGroupHook.createFolderRow(ctx: Context): View {
    val dark = isDark(ctx)
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
        setBackgroundColor(if (dark) Color.parseColor("#2c2c2e") else Color.WHITE)
        // 关键：**不要**让收纳组行自己 clickable。
        // 行一旦 clickable 就会成为触摸目标，把 DOWN 吃掉：
        //   · ListView 收不到 DOWN → 从这一行上起手拖动无法滚动列表
        //   · 谁来处理这次点击变得不确定（行监听器 vs 微信自己的列表项监听器）
        //     → 微信偶尔按虚拟位置当真实会话处理，表现就是「点一下就闪退」
        // 正解：行保持「不可点」，交给 ListView 原生处理（有按压反馈、能拖动滚动），
        // 点击/长按统一由 AdapterView.performItemClick / performLongPress 那两处拦截。
        isEnabled = true
        isClickable = false
        isLongClickable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }
    // 分组图标：容器 44dp，emoji 用 28sp + includeFontPadding=false，
    // 让字形基本撑满圆角方块（原来 40dp/17sp 显得中间一小坨）
    val badge = TextView(ctx).apply {
        text = "📁"
        textSize = 28f
        includeFontPadding = false
        gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(ctx, 13).toFloat()
        bg.setColor(if (dark) Color.parseColor("#3a3a3c") else Color.parseColor("#eef3ff"))
        background = bg
    }
    row.addView(badge, LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)).apply { rightMargin = dp(ctx, 12) })

    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val name = TextView(ctx).apply {
        textSize = 15.5f
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (dark) Color.WHITE else Color.parseColor("#1a1a1a"))
    }
    val sub = TextView(ctx).apply {
        textSize = 12f
        setTextColor(if (dark) Color.parseColor("#9a9a9e") else Color.parseColor("#8a8a8e"))
    }
    col.addView(name)
    col.addView(sub)
    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    val arrow = TextView(ctx).apply {
        textSize = 15f
        setTextColor(if (dark) Color.parseColor("#9a9a9e") else Color.parseColor("#8a8a8e"))
    }
    row.addView(arrow)

    // 未读角标（红点/数字），bindFolderRow 里按未读数显隐
    val unread = TextView(ctx).apply {
        textSize = 11f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(ctx, 9).toFloat()
        bg.setColor(Color.parseColor("#FA5151"))
        background = bg
        visibility = View.GONE
    }
    row.addView(unread, LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)).apply { leftMargin = dp(ctx, 8) })

    val holder = FolderHolder(badge, name, sub, arrow, unread)
    row.tag = holder
    // 点击/长按不在这里处理：见 installItemClickInterceptor / installItemLongPressInterceptor
    return row
}

internal fun ConversationGroupHook.bindFolderRow(view: View, group: WxGroup, inListCount: Int, unreadCount: Int = 0) {
    val holder = view.tag as? FolderHolder ?: return
    holder.groupId = group.id
    val opened = openGroupId == group.id
    val total = group.members.size
    holder.name.text = group.name.ifBlank { "未命名分组" }
    holder.name.textSize = if (opened) 18f else 15.5f
    // 打开态：这一行就是二级页面的标题栏，副标题给出「成员数 + 未读总数 + 怎么返回」
    val unreadTip = if (unreadCount > 0) " · $unreadCount 条未读" else ""
    holder.sub.text = when {
        // 打开态右侧已有「‹ 返回」，副标题别再写"怎么退"，避免换行
        opened -> "$inListCount 个会话$unreadTip"
        total == 0 -> "空分组 · 长按管理"
        inListCount == 0 -> "$total 个成员$unreadTip · 均不在会话列表"
        else -> "$total 个成员$unreadTip · 点开查看"
    }
    holder.arrow.text = if (opened) "‹ 返回" else "▸"
    holder.arrow.textSize = if (opened) 14f else 15f
    val dark2 = isDark(view.context)
    view.setBackgroundColor(
        if (opened) {
            if (dark2) 0xFF3A3A3C.toInt() else 0xFFEFF3FA.toInt()
        } else {
            if (dark2) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
        }
    )
    // 标题栏状态：图标放大一点，底部分隔线靠背景色区分，视觉上像「页面头部」
    val size = if (opened) dp(view.context, 50) else dp(view.context, 44)
    (holder.icon.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
        lp.width = size
        lp.height = size
        holder.icon.layoutParams = lp
    }
    // 圆角跟着容器走，保持「超椭圆」观感
    (holder.icon.background as? GradientDrawable)?.cornerRadius = size / 3.2f
    // 字形撑满方块：44dp→28sp，50dp→32sp（includeFontPadding 已在创建时关掉）
    holder.icon.textSize = if (opened) 32f else 28f

    // 图标：显式指定的优先，否则按 id 哈希取默认；底色用同一 id 的哈希色（带透明度当浅底）
    val icon = group.icon.ifBlank { GroupTheme.iconOf(group.id) }
    holder.icon.text = icon
    val c = GroupTheme.colorOf(group.id)
    val dark = isDark(view.context)
    (holder.icon.background as? GradientDrawable)?.setColor(
        if (dark) (c and 0x00FFFFFF) or 0x2A000000 else (c and 0x00FFFFFF) or 0x22000000
    )

    // 未读角标
    if (unreadCount > 0) {
        holder.unread.visibility = View.VISIBLE
        holder.unread.text = if (unreadCount > 99) "99+" else unreadCount.toString()
    } else {
        holder.unread.visibility = View.GONE
    }
}
