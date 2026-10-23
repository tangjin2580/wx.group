package io.github.wxgroup.reborn.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.core.WxSettings

/**
 * 收纳组行的 View：构建 + 绑定。
 *
 * ── 设计取向：长得像微信自己的行，而不是「贴上去的一层」──
 * 早期版本为了「分组够显眼」加了左侧色条 + 彩色行底 + 加粗标题，结果整块看上去
 * 像另一个 App 的列表硬塞进微信（用户原话：「不太沉浸，页面比较割裂」）。
 * 现在的做法是**用微信自己的语言做区分**：
 *   · 图标 = 2×2 成员拼接（微信「折叠的群聊」行就是这个样子）
 *   · 分组区上方加一行「分组」小节标题（微信列表里本来就有小节标题这种结构）
 *   · 行本身：底色、字色、字号、间距全部和微信会话行一致，不加额外装饰
 *
 * ⚠️ 行保持「不可点」（isClickable=false）：一旦 clickable 就会吃掉触摸 DOWN，
 * 从这一行起手无法滚动列表，而且会和微信自己的列表项监听器抢这次点击。
 * 点击/长按统一交给 ItemIntercept 那两处 framework 级拦截。
 *
 * ── 视觉基线：对齐微信原生会话行（8.0.x）──
 *   行高 64dp = 头像 48dp + 上下各 8dp
 *   标题 17sp（常规字重）/ 副标题 14sp
 *   标题 #191919、副标题 #999999（深色：#D6D6D6 / #8A8A8A）
 *   行背景 #FFFFFF / #191919
 */

// ==================================================================================
// 配色
//
// 分两层：
//   1. **主题色板**（GroupTheme.Palette）—— 收纳组自己的颜色（色条/图标底/行底/字色），
//      支持 8 套主题 + 单组覆盖，是本模块负责创建的那部分 View 唯一允许的取色来源。
//   2. **微信原生基线**（WxColors）—— 只在「主题没覆盖到的兜底场景」用（例如未读角标、
//      拿不到配置时的默认字色）。绝不去改微信原生会话行的颜色。
// ==================================================================================
private object WxColors {
    const val LIGHT_ROW = 0xFFFFFFFF.toInt()
    const val DARK_ROW = 0xFF191919.toInt()
    const val LIGHT_HEADER = 0xFFF5F5F5.toInt()   // 打开态标题栏，比列表底色略深一档
    const val DARK_HEADER = 0xFF232323.toInt()
    const val LIGHT_TITLE = 0xFF191919.toInt()
    const val DARK_TITLE = 0xFFD6D6D6.toInt()
    const val LIGHT_SUB = 0xFF9A9A9A.toInt()
    const val DARK_SUB = 0xFF8A8A8A.toInt()
    const val LIGHT_CHEVRON = 0xFFC7C7CC.toInt()
    const val DARK_CHEVRON = 0xFF5A5A5E.toInt()
    const val BADGE = 0xFFFA5151.toInt()          // 微信品牌红
    const val READ_FG = 0xFF07C160.toInt()        // 微信品牌绿（「已读」按钮文字）
    const val READ_BG_LIGHT = 0x1407C160          // 8% 绿，和微信浅色底的标签一个观感
    const val READ_BG_DARK = 0x2407C160
}

/**
 * 当前深浅色：把主题的 darkMode（system / light / dark）和微信自己的夜间模式合起来判。
 * 注意只能影响**我们创建的行**，不能拿去改微信任何原生控件。
 */
internal fun ConversationGroupHook.themeDark(ctx: Context, s: WxSettings): Boolean = when (s.darkMode) {
    GroupTheme.Mode.LIGHT -> false
    GroupTheme.Mode.DARK -> true
    else -> isDark(ctx)
}

// ==================================================================================
// 分组行 View
// ==================================================================================
internal fun ConversationGroupHook.createFolderRow(ctx: Context, clickable: Boolean = false): View {
    val dark = isDark(ctx)
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 左边只留 8dp：剩下的 8dp 由「色条(3dp) + 间距(5dp)」补上，
        // 图标左边缘仍然落在 16dp —— 和微信会话行的头像对齐，不会显得缩进了一截。
        setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        // 底色由外层 wrapper 统一给（行本身透明），避免两处各画一层。
        // 关键：ListView 分支下**不要**让收纳组行自己 clickable。
        // 行一旦 clickable 就会成为触摸目标，把 DOWN 吃掉：
        //   · ListView 收不到 DOWN → 从这一行上起手拖动无法滚动列表
        //   · 谁来处理这次点击变得不确定（行监听器 vs 微信自己的列表项监听器）
        //     → 微信偶尔按虚拟位置当真实会话处理，表现就是「点一下就闪退」
        // 正解：行保持「不可点」，交给 ListView 原生处理（有按压反馈、能拖动滚动），
        // 点击/长按统一由 AdapterView.performItemClick / performLongPress 那两处拦截。
        //
        // RecyclerView 分支相反：RV 不会因为这行 clickable 就丢掉滚动，而 RV 的点击
        // 又不走 AdapterView 那套拦截 —— 所以那条路径传 clickable=true，由 RecyclerViewPath
        // 自己挂 OnClickListener / OnLongClickListener。
        isEnabled = true
        isClickable = clickable
        isLongClickable = clickable
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    // 左侧竖向色条：分组唯一的「大面积」色彩来源（宽 3dp，长 = 图标的 0.66），
    // 圆角取宽度的一半做成胶囊。行底色保持中性白，不再整行铺色。
    val bar = View(ctx).apply {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(ctx, 3).toFloat() / 2f   // 圆角 = 宽度的一半 → 胶囊
        bg.setColor(WxColors.READ_FG)
        background = bg
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    row.addView(bar, LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 32)).apply { rightMargin = dp(ctx, 5) })

    // 打开态的返回箭头：放最左边（页面标题栏语义），平时 GONE。
    // 整行点击本身就是「返回」，这里只做视觉引导 + 加大触控区域。
    val back = TextView(ctx).apply {
        text = "‹"
        textSize = 28f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(if (dark) WxColors.DARK_CHEVRON else WxColors.LIGHT_CHEVRON)
        visibility = View.GONE
    }
    row.addView(back, LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 48)).apply { rightMargin = dp(ctx, 4) })

    // 分组图标：48dp（微信头像尺寸），emoji 用 0.58 倍容器大小，基本撑满圆角方块。
    // 底色中性灰 —— 不跟着分组染色，保持和微信会话行一个观感。
    val icon = TextView(ctx).apply {
        text = "📁"
        textSize = 28f
        includeFontPadding = false
        gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(ctx, 11).toFloat()
        bg.setColor(if (dark) 0xFF2E2E2E.toInt() else 0xFFF2F2F2.toInt())
        background = bg
    }
    row.addView(icon, LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)).apply { rightMargin = dp(ctx, 12) })

    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val name = TextView(ctx).apply {
        textSize = 17f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (dark) WxColors.DARK_TITLE else WxColors.LIGHT_TITLE)
    }
    val sub = TextView(ctx).apply {
        textSize = 14f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (dark) WxColors.DARK_SUB else WxColors.LIGHT_SUB)
    }
    col.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    col.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 2) })
    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    // 「已读」按钮：只在有未读时出现，点了把这个分组里所有未读会话标为已读。
    // ⚠️ 不挂 OnClickListener —— 行内任何 clickable 子 View 都会吃掉 DOWN，
    // 从按钮上起手就没法滚动列表。点击命中由 ItemIntercept 用按下坐标判定。
    val readBtn = TextView(ctx).apply {
        text = "已读"
        textSize = 12f
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
        setTextColor(WxColors.READ_FG)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(ctx, 13).toFloat()
        bg.setColor(if (dark) WxColors.READ_BG_DARK else WxColors.READ_BG_LIGHT)
        background = bg
        setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
        isClickable = false
        isLongClickable = false
        visibility = View.GONE
    }
    row.addView(readBtn, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = dp(ctx, 8) })

    // 未读角标：微信同款 18dp 高红色胶囊。宽度自适应 —— 「99+」按老写法固定 18dp 会被裁字。
    val unread = TextView(ctx).apply {
        textSize = 11f
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(ctx, 9).toFloat()
        bg.setColor(WxColors.BADGE)
        background = bg
        visibility = View.GONE
    }
    row.addView(unread, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 18)
    ).apply {
        leftMargin = dp(ctx, 8)
        marginStart = dp(ctx, 8)
    })

    // 右侧指示箭头：常态「›」；打开态隐藏（返回箭头已经在最左边）
    val arrow = TextView(ctx).apply {
        text = "›"
        textSize = 22f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(if (dark) WxColors.DARK_CHEVRON else WxColors.LIGHT_CHEVRON)
    }
    row.addView(arrow, LinearLayout.LayoutParams(dp(ctx, 16), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dp(ctx, 4)
    })

    // 行间分隔线：只画一条 0.5dp 的极浅灰线，左端对齐图标（16dp），右端通到屏幕边 ——
    // 和微信会话行的分割线一个位置、一个观感。分组之间靠它分开，不再靠「底色深浅差」。
    val divider = View(ctx).apply {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.setColor(if (dark) 0xFF262626.toInt() else 0xFFEDEDED.toInt())
        background = bg
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val divH = maxOf(1, (ctx.resources.displayMetrics.density * 0.5f).toInt())

    // 外层容器（垂直）：内容行 + 分隔线。它是 getView 真正返回的那一个 View，
    // 也是背景色的承载体 —— 内容行保持透明，避免两层底色叠加出脏色。
    val wrapper = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(if (dark) WxColors.DARK_ROW else WxColors.LIGHT_ROW)
        isEnabled = true
        isClickable = clickable
        isLongClickable = clickable
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }
    wrapper.addView(row, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    wrapper.addView(divider, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, divH
    ).apply { marginStart = dp(ctx, 16) })

    val holder = FolderHolder(icon, name, sub, arrow, unread, back, readBtn, bar, row, divider)
    wrapper.tag = holder
    // 点击/长按不在这里处理：见 installItemClickInterceptor / installItemLongPressInterceptor
    return wrapper
}

/**
 * 「分组」小节标题行。
 *
 * 微信自己的列表里本来就有「小节标题」这种结构（通讯录的字母索引、公众号列表的分区），
 * 用它把分组块从会话流里「框」出来，比给每一行加装饰自然得多，也不显得是外挂。
 */
internal fun ConversationGroupHook.createSectionRow(ctx: Context): View {
    val dark = isDark(ctx)
    return TextView(ctx).apply {
        text = "分组"
        textSize = 13f
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(if (dark) WxColors.DARK_SUB else WxColors.LIGHT_SUB)
        // 上下留白压紧（10/4），把省下来的空间还给会话本身
        setPadding(dp(ctx, 17), dp(ctx, 10), dp(ctx, 16), dp(ctx, 4))
        setBackgroundColor(if (dark) WxColors.DARK_ROW else WxColors.LIGHT_ROW)
        // 小节行不参与点击：tag 用常量串，convertView 复用时靠它认出来
        tag = SECTION_TAG
        isClickable = false
        isLongClickable = false
    }
}

internal fun ConversationGroupHook.bindFolderRow(
    view: View,
    group: WxGroup,
    inListCount: Int,
    unreadCount: Int = 0,
    senders: List<String> = emptyList()
) {
    val holder = view.tag as? FolderHolder ?: return
    val ctx = view.context
    val settings = wxSettings()
    holder.groupId = group.id
    val opened = openGroupId == group.id
    val total = group.members.size
    val dark = themeDark(ctx, settings)
    val compact = settings.compactRows

    // ── 主题取色 ──────────────────────────────────────────────────────
    // 优先级：单组自定义色 > 主题色板（按显示顺序轮转）> 主题主色。
    // 每次 bind 都重算：列表复用时行会被绑到别的分组上，颜色必须跟着换，否则串色。
    val palette = GroupTheme.of(settings.themeId).palette(dark)
    val accent = GroupTheme.colorForGroup(
        group.id,
        sortedGroups().indexOfFirst { it.id == group.id },
        settings
    )

    holder.name.text = group.name.ifBlank { "未命名分组" }

    // ── 尺寸：所有档位一次算好，避免下面每处再判断 ──────────────────────
    // 常态 48dp（微信头像）/ 紧凑 40dp / 打开态（标题栏）44dp
    val iconSize = when {
        opened -> 44
        compact -> 40
        else -> 48
    }
    val vPad = if (compact) 6 else 8
    // 内边距给内容行（外层 wrapper 不带 padding），左侧 8dp + 色条 3dp + 间距 5dp = 16dp，
    // 图标左边缘与微信会话行头像对齐。
    holder.content.setPadding(dp(ctx, 8), dp(ctx, vPad), dp(ctx, 16), dp(ctx, vPad))

    holder.name.textSize = when {
        opened -> 17f
        compact -> 15.5f
        else -> 17f
    }
    // 标题栏语义下加粗；列表里的行保持常规字重（微信会话标题不加粗）
    holder.name.setTypeface(null, if (opened) Typeface.BOLD else Typeface.NORMAL)
    holder.sub.textSize = if (compact) 13f else 14f
    // 行间距：标题与副标题之间留一点呼吸，但不撑高行高（行高由 48dp 图标 + 8dp 上下内边距决定）
    holder.name.setLineSpacing(0f, 1.05f)
    holder.sub.setLineSpacing(0f, 1.05f)

    (holder.icon.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
        if (lp.width != dp(ctx, iconSize) || lp.height != dp(ctx, iconSize)) {
            lp.width = dp(ctx, iconSize)
            lp.height = dp(ctx, iconSize)
            holder.icon.layoutParams = lp
        }
    }
    holder.icon.textSize = iconSize * 0.58f
    // 图标容器圆角统一取「边长的 1/4」：48dp→12dp、44dp→11dp、40dp→10dp，
    // 三档看起来是同一枚控件缩放，不会出现圆角忽大忽小。
    (holder.icon.background as? GradientDrawable)?.cornerRadius = dp(ctx, iconSize) * 0.25f
    holder.icon.text = group.icon.ifBlank { GroupTheme.iconOf(group.id) }

    // 色条：长度 = 图标的 0.66（48dp→32dp），宽 3dp，颜色 = 该分组的强调色
    (holder.bar.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
        val h = dp(ctx, (iconSize * 0.66f).toInt())
        if (lp.height != h) {
            lp.height = h
            holder.bar.layoutParams = lp
        }
    }
    (holder.bar.background as? GradientDrawable)?.setColor(accent)

    // ── 文案 ─────────────────────────────────────────────────────────
    val unreadTip = if (unreadCount > 0) " · $unreadCount 条未读" else ""
    val closedSub = when {
        // 「A、B 等 N 条未读」——微信折叠群聊的副标题同款句式；发送者拿不到时退回成员数
        settings.showSenders && unreadCount > 0 && senders.isNotEmpty() -> {
            if (senders.size == 1) "${senders[0]} 发来消息"
            else "${senders[0]}、${senders[1]} 等 $unreadCount 条未读"
        }
        total == 0 -> "空分组 · 长按管理"
        inListCount == 0 -> "$total 个成员$unreadTip · 均不在会话列表"
        else -> "$total 个成员$unreadTip"
    }
    holder.sub.text = if (opened) {
        if (inListCount == 0) "该分组暂无会话 · 返回后长按会话添加" else "$inListCount 个会话$unreadTip"
    } else closedSub

    // ── 打开态 = 页面标题栏 ──────────────────────────────────────────
    holder.back.visibility = if (opened) View.VISIBLE else View.GONE
    holder.arrow.visibility = if (opened) View.GONE else View.VISIBLE
    // 排序模式下右侧箭头换成「≡」，提示当前可以拖动
    if (!opened) holder.arrow.text = if (reorderMode) "≡" else "›"

    // ── 行底色：普通状态一律中性（白 / 深色底），**不给整行铺色** ──────
    // 只有「当前打开的分组」用主题的浅色背景（selectedBackground 再混一点本组强调色），
    // 这样一屏里永远只有一行带底色，既看得出选中，也不会变成一块彩色面板。
    view.setBackgroundColor(
        if (opened) GroupTheme.blend(palette.selectedBackground, accent, 0.14f)
        else palette.normalBackground
    )
    // 分隔线：极浅灰，左端对齐图标
    (holder.divider.background as? GradientDrawable)?.setColor(palette.divider)

    // ── 文字 / 图标容器 / 箭头：全部来自同一套色板 ────────────────────
    holder.name.setTextColor(palette.textPrimary)
    holder.sub.setTextColor(palette.textSecondary)
    // 箭头统一用最弱的灰（theme.disabled），弱化它的存在感
    holder.arrow.setTextColor(palette.disabled)
    holder.back.setTextColor(palette.disabled)
    // 图标容器 = 主题浅色版本 + 一点本组强调色，和左侧色条是同一个色系
    (holder.icon.background as? GradientDrawable)?.setColor(
        GroupTheme.blend(palette.iconBackground, accent, 0.22f)
    )
    // 单色符号用本组的强调色画（像微信自己的单色图标，只靠形状 + 主题色区分）；
    // 彩色 emoji 由字体自己上色，setTextColor 对它无效，保持原样即可。
    if (GroupTheme.isGlyph(holder.icon.text?.toString().orEmpty())) {
        holder.icon.setTextColor(accent)
    }
    // 「已读」胶囊：低饱和浅底 + 本组强调色文字，不做大色块
    holder.readBtn.setTextColor(accent)
    (holder.readBtn.background as? GradientDrawable)?.setColor(
        GroupTheme.blend(palette.normalBackground, accent, 0.10f)
    )

    // ── 未读角标 ─────────────────────────────────────────────────────
    // 打开态不显示组级角标：里面每个会话自己都有角标，再在标题上重复一遍是噪音。
    if (unreadCount > 0 && !opened) {
        holder.unread.visibility = View.VISIBLE
        // 未读角标恒为微信品牌红：靠红色识别未读是最强的心智，不能跟着主题变色。
        (holder.unread.background as? GradientDrawable)?.setColor(GroupTheme.UNREAD_BADGE)
        if (settings.badgeDot) {
            holder.unread.text = ""
            holder.unread.setPadding(0, 0, 0, 0)
            val lp = holder.unread.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.width = dp(ctx, 10)
                lp.height = dp(ctx, 10)
                holder.unread.layoutParams = lp
            }
            (holder.unread.background as? GradientDrawable)?.cornerRadius = dp(ctx, 5).toFloat()
        } else {
            holder.unread.text = if (unreadCount > 99) "99+" else unreadCount.toString()
            // 宽度交给 WRAP_CONTENT，靠左右 padding 撑成胶囊；「99+」不会再被裁掉。
            holder.unread.setPadding(dp(ctx, 5), 0, dp(ctx, 5), 0)
            val lp = holder.unread.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.height = dp(ctx, 18)
                holder.unread.layoutParams = lp
            }
            (holder.unread.background as? GradientDrawable)?.cornerRadius = dp(ctx, 9).toFloat()
        }
    } else {
        holder.unread.visibility = View.GONE
    }

    // ── 「已读」按钮 ──────────────────────────────────────────────────
    // 只在有未读时出现；打开态也保留（组内几十条未读时，逐个会话点太慢）。
    holder.readBtn.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE
    if (unreadCount > 0) {
        holder.readBtn.contentDescription = "把「${group.name}」的 $unreadCount 条未读全部标为已读"
    }

    // 无障碍：让 TalkBack 读成「分组名，N 个成员，M 条未读」而不是把 emoji 和箭头逐个念出来
    val cd = buildString {
        append(group.name.ifBlank { "未命名分组" })
        append("，").append(total).append(" 个成员")
        if (unreadCount > 0) append("，").append(unreadCount).append(" 条未读")
        if (opened) append("，当前已打开")
    }
    view.contentDescription = cd
    holder.icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    holder.arrow.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    holder.back.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}
