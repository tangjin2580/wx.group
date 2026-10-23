package io.github.wxgroup.reborn.ui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.core.WxSettings

/**
 * 外观与主题：全局主题 / 深浅色 / 单组配色。
 *
 * ── 存储 ──
 * 全部落在 [WxSettings]（模块 App 的 SharedPreferences + /data/local/tmp 镜像），
 * 走的是分组那条已经跑通的通道：保存后微信里的 Hook 2s 内就能看到（Provider 通知更快）。
 * 单组配色单独存在 `WxSettings.groupColors` 里，**不写进 WxGroup**——
 * 分组/成员/签名数据保持纯净，导入导出与微信侧落盘都不受影响。
 *
 * ── 不重建 Activity ──
 * 切主题只改配色（预览用 240ms 过渡），不 recreate 页面，避免整页闪一下；
 * 只有切「深浅色模式」才需要重建（AppCompat 的夜间模式本来就是 recreate 生效的）。
 */
class ThemeActivity : AppCompatActivity() {

    /** 当前设置（每次改动都整体写回，字段不会被别的入口冲掉） */
    private lateinit var settings: WxSettings
    private var groups: List<WxGroup> = emptyList()

    /** 预览的颜色动画统一托管：切主题前先取消上一批，onDestroy 再清一次 */
    private val previewAnimators = Motion.ColorAnimators()

    /** 主题卡的句柄（切主题时只更新选中态描边/打勾） */
    private class ThemeCard(val card: View, val check: TextView)

    private val cards = LinkedHashMap<String, ThemeCard>()

    /** 「当前效果」预览：切主题时在这儿做 240ms 颜色过渡，全局只有一处 */
    private var curRow: PreviewRow? = null
    private var curPalette: GroupTheme.Palette? = null
    private var curAccent = 0

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    /** 预览用不用深色：跟随系统时看当前 uiMode，否则听用户选的 */
    private fun previewDark(): Boolean = when (settings.darkMode) {
        GroupTheme.Mode.LIGHT -> false
        GroupTheme.Mode.DARK -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme)
        settings = GroupStore.getSettings(this)
        groups = GroupStore.sortForDisplay(GroupStore.getGroups(this))

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        bindModes()
        renderCurrentPreview()
        renderThemes()
        renderGroups()

        findViewById<Button>(R.id.btn_reset_theme).setOnClickListener {
            // 只清主题相关的三个字段，显示偏好（红点/紧凑/隐藏空组…）原样保留
            settings = settings.copy(
                themeId = GroupTheme.Id.DEFAULT,
                darkMode = GroupTheme.Mode.DEFAULT,
                groupColors = emptyMap()
            )
            GroupStore.putSettings(this, settings)
            applyNightMode()
            recreate()
        }
        restyleResetButton()
    }

    override fun onResume() {
        super.onResume()
        // 分组可能刚在微信里改过（新建/删除），回来重新渲染一次
        val fresh = GroupStore.sortForDisplay(GroupStore.getGroups(this))
        if (fresh != groups) {
            groups = fresh
            renderGroups()
        }
    }

    override fun onDestroy() {
        // Activity 没了，动画必须跟着停：否则 animator 还攥着 Row / TextView 的引用，
        // 每帧往一个已经 detach 的 View 上写颜色
        previewAnimators.cancelAll()
        super.onDestroy()
    }

    // ==================================================================================
    // 深浅色模式
    // ==================================================================================
    private fun bindModes() {
        val dark = previewDark()
        bindModeButton(R.id.btn_mode_system, settings.darkMode == GroupTheme.Mode.SYSTEM) {
            setMode(GroupTheme.Mode.SYSTEM)
        }
        bindModeButton(R.id.btn_mode_light, settings.darkMode == GroupTheme.Mode.LIGHT) {
            setMode(GroupTheme.Mode.LIGHT)
        }
        bindModeButton(R.id.btn_mode_dark, settings.darkMode == GroupTheme.Mode.DARK) {
            setMode(GroupTheme.Mode.DARK)
        }
        if (dark) { /* 仅用于让预览取深色色板，按钮态与 uiMode 无关 */ }
    }

    private fun bindModeButton(id: Int, selected: Boolean, onClick: () -> Unit) {
        val b = findViewById<Button>(id)
        // 选中 = 实心主按钮，未选中 = 描边次要按钮。
        // 背景在代码里按**当前主题主色**生成（以前是写死的品牌绿 drawable，
        // 选了「天空蓝」主题，这三个按钮还是绿的 —— 预览和实际对不上）。
        val primary = GroupTheme.of(settings.themeId).palette(previewDark()).primary
        b.background = buttonBg(selected, primary)
        b.setTextColor(if (selected) GroupTheme.onColorFor(primary) else primary)
        b.setOnClickListener { onClick() }
    }

    /**
     * 按钮底：主色实心 / 主色描边，外面套 [RippleDrawable] 保留按下水波纹。
     *
     * 不用 `setBackgroundResource` 那两个 drawable —— 它们的颜色写死在 XML 里，
     * 改不了；这里改成代码生成，但**波纹不能省**，否则按下去没有任何反馈。
     */
    private fun buttonBg(filled: Boolean, primary: Int): android.graphics.drawable.Drawable {
        val radius = resources.getDimension(R.dimen.wg_radius_btn)
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            if (filled) setColor(primary)
            else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1f), primary)
            }
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        val ripple = android.content.res.ColorStateList.valueOf(
            if (filled) 0x33FFFFFF.toInt() else 0x14000000
        )
        return android.graphics.drawable.RippleDrawable(ripple, shape, mask)
    }

    private fun setMode(mode: String) {
        settings = settings.copy(darkMode = mode)
        GroupStore.putSettings(this, settings)
        applyNightMode()
        // 夜间模式切换必然伴随 Activity 重建（AppCompat 机制），这里主动 recreate 一次，
        // 免得系统重建的时机不确定导致「按钮亮了、页面还是旧的」
        recreate()
    }

    private fun applyNightMode() {
        AppCompatDelegate.setDefaultNightMode(
            when (settings.darkMode) {
                GroupTheme.Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                GroupTheme.Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // ==================================================================================
    // 主题卡片
    // ==================================================================================
    /** 顶部「当前效果」：把选中的主题按当前深浅色渲染成一条真实的收纳组行 */
    private fun renderCurrentPreview() {
        val container = findViewById<LinearLayout>(R.id.ll_current)
        container.removeAllViews()
        val theme = GroupTheme.of(settings.themeId)
        val palette = theme.palette(previewDark())
        val accent = theme.swatches.firstOrNull() ?: palette.primary
        val pv = buildPreviewRow(palette, accent)
        container.addView(pv.row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        curRow = pv
        curPalette = palette
        curAccent = accent
    }

    private fun renderThemes() {
        val container = findViewById<LinearLayout>(R.id.ll_themes)
        container.removeAllViews()
        cards.clear()
        val dark = previewDark()
        for (t in GroupTheme.THEMES) {
            val selected = t.id == settings.themeId
            val palette = t.palette(dark)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = cardBg(selected, palette.primary)
                elevation = resources.getDimension(R.dimen.wg_elevation_card)
                isClickable = true
                setOnClickListener { selectTheme(t.id) }
            }
            val check = TextView(this).apply {
                text = "✓"
                textSize = 16f
                setTextColor(palette.primary)
                visibility = if (selected) View.VISIBLE else View.GONE
                includeFontPadding = false
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@ThemeActivity).apply {
                    text = t.label
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@ThemeActivity, R.color.wg_text_primary))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(check)
            })

            // 色样：主色 + 强调色 + 行底色 + 图标底色（让用户看到整套色板而不只是主色）
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(dp(16f), dp(16f))
                lp.rightMargin = dp(6f)
                listOf(palette.accent, palette.iconBackground, palette.selectedBackground, palette.textSecondary).forEach { c ->
                    addView(View(this@ThemeActivity).apply {
                        background = circle(c)
                    }, lp)
                }
                val lp2 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp2.topMargin = dp(8f)
                addView(TextView(this@ThemeActivity).apply {
                    text = getString(R.string.theme_sample_hint)
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(this@ThemeActivity, R.color.wg_text_secondary))
                }, lp2)
            })

            // 群组行预览：和微信里的收纳组长得一样（色条 + 图标块 + 标题 + 副标题 + 红角标）
            val pv = buildPreviewRow(palette, t.swatches.firstOrNull() ?: palette.primary)
            card.addView(pv.row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10f) })
            cards[t.id] = ThemeCard(card, check)

            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) })
        }
    }

    private data class PreviewRow(
        val row: View, val bar: View, val icon: TextView, val name: TextView, val sub: TextView, val accent: Int
    )

    /** 迷你版「收纳组行」：结构跟微信里那行一一对应，方便预览配色 */
    private fun buildPreviewRow(palette: GroupTheme.Palette, accent: Int): PreviewRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(8f), dp(12f), dp(8f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f).toFloat()
                // 预览和微信里一样：白底（深色主题下是深色底）+ 一道极浅描边，
                // 颜色只出现在色条和图标容器上
                setColor(palette.normalBackground)
                setStroke(dp(1f), palette.divider)
            }
        }
        val bar = View(this).apply { background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(1.5f).toFloat()
            setColor(accent)
        } }
        row.addView(bar, LinearLayout.LayoutParams(dp(3f), dp(26f)).apply { rightMargin = dp(5f) })

        val icon = TextView(this).apply {
            text = "📁"
            textSize = 20f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10f).toFloat()
                setColor(GroupTheme.blend(palette.iconBackground, accent, 0.22f))
            }
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { rightMargin = dp(10f) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = TextView(this).apply {
            text = getString(R.string.theme_preview_name)
            textSize = 15f
            maxLines = 1
            setTextColor(palette.textPrimary)
        }
        val sub = TextView(this).apply {
            text = getString(R.string.theme_preview_sub)
            textSize = 12f
            maxLines = 1
            setTextColor(palette.textSecondary)
        }
        col.addView(name)
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val badge = TextView(this).apply {
            text = "3"
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(5f), 0, dp(5f), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9f).toFloat()
                setColor(GroupTheme.UNREAD_BADGE)   // 未读恒为红色，不随主题变
            }
        }
        row.addView(badge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(18f)
        ).apply { leftMargin = dp(8f) })
        return PreviewRow(row, bar, icon, name, sub, accent)
    }

    /** 直接把预览刷成目标配色（构建时用） */
    private fun applyPreview(pv: PreviewRow, p: GroupTheme.Palette, accent: Int) {
        (pv.row.background as? GradientDrawable)?.setColor(p.normalBackground)
        (pv.bar.background as? GradientDrawable)?.setColor(accent)
        (pv.icon.background as? GradientDrawable)?.setColor(GroupTheme.blend(p.iconBackground, accent, 0.22f))
        pv.name.setTextColor(p.textPrimary)
        pv.sub.setTextColor(p.textSecondary)
    }

    /**
     * 颜色过渡：240ms，从旧配色混到新配色（系统「减少动效」时直接跳终值）。
     *
     * 开新动画前先 [Motion.ColorAnimators.cancelAll] —— 快速连点主题卡时，
     * 上一批 animator 若还活着会往同一批 View 里写旧颜色，看起来就是「跳色」。
     */
    private fun animatePreview(
        pv: PreviewRow,
        from: GroupTheme.Palette, fromAccent: Int,
        to: GroupTheme.Palette, toAccent: Int
    ) {
        previewAnimators.cancelAll()
        Motion.colorAnimator(this, from.normalBackground, to.normalBackground,
            { c -> (pv.row.background as? GradientDrawable)?.setColor(c) }, Motion.THEME_MS)
            ?.let { previewAnimators.start(it) }
        Motion.colorAnimator(this, fromAccent, toAccent, { c ->
            (pv.bar.background as? GradientDrawable)?.setColor(c)
        }, Motion.THEME_MS)?.let { previewAnimators.start(it) }
        Motion.colorAnimator(this, from.iconBackground, to.iconBackground, { c ->
            (pv.icon.background as? GradientDrawable)?.setColor(GroupTheme.blend(c, toAccent, 0.22f))
        }, Motion.THEME_MS)?.let { previewAnimators.start(it) }
        Motion.colorAnimator(this, from.textPrimary, to.textPrimary,
            { c -> pv.name.setTextColor(c) }, Motion.THEME_MS)?.let { previewAnimators.start(it) }
        Motion.colorAnimator(this, from.textSecondary, to.textSecondary,
            { c -> pv.sub.setTextColor(c) }, Motion.THEME_MS)?.let { previewAnimators.start(it) }
    }

    private fun selectTheme(id: String) {
        if (id == settings.themeId) return
        settings = settings.copy(themeId = id)
        GroupStore.putSettings(this, settings)
        val dark = previewDark()
        val theme = GroupTheme.of(id)
        val toPalette = theme.palette(dark)
        val toAccent = theme.swatches.firstOrNull() ?: toPalette.primary

        // 选中态：打勾 + 卡片描边换成主题主色（卡片本身不重建，避免整页跳动）
        cards.forEach { (tid, c) ->
            val on = tid == id
            c.check.visibility = if (on) View.VISIBLE else View.GONE
            c.check.setTextColor(toPalette.primary)
            c.card.background = cardBg(on, if (on) toPalette.primary else 0)
        }
        // 「当前效果」做一次 240ms 过渡
        val pv = curRow
        val from = curPalette
        if (pv != null && from != null) {
            animatePreview(pv, from, curAccent, toPalette, toAccent)
        }
        curPalette = toPalette
        curAccent = toAccent
        // 单组配色的候选色板来自当前主题，跟着换
        renderGroups()
        // 模式按钮 / 重置按钮的颜色也取自当前主题主色，切主题后必须重刷，
        // 否则选了「莫兰迪」顶部按钮还是绿的（预览和实际对不上）
        bindModes()
        restyleResetButton()
        toastApplied()
    }

    /** 「重置主题」按钮：与模式按钮同一套主题色描边样式（XML 里的颜色写死了，只能代码覆盖） */
    private fun restyleResetButton() {
        val b = findViewById<Button>(R.id.btn_reset_theme)
        val primary = GroupTheme.of(settings.themeId).palette(previewDark()).primary
        b.background = buttonBg(false, primary)
        b.setTextColor(primary)
    }

    private fun toastApplied() {
        android.widget.Toast.makeText(this, R.string.theme_applied, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ==================================================================================
    // 单组配色
    // ==================================================================================
    private fun renderGroups() {
        val container = findViewById<LinearLayout>(R.id.ll_groups)
        container.removeAllViews()
        val empty = findViewById<TextView>(R.id.tv_groups_empty)
        if (groups.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        val dark = previewDark()
        val swatches = GroupTheme.of(settings.themeId).swatches
        for ((index, g) in groups.withIndex()) {
            val custom = settings.groupColors[g.id]
            val color = custom ?: GroupTheme.colorForGroup(g.id, index, settings)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = cardBg(false, 0)
                elevation = resources.getDimension(R.dimen.wg_elevation_card)
            }

            val dot = View(this).apply { background = circle(color) }
            val title = TextView(this).apply {
                text = g.name
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@ThemeActivity, R.color.wg_text_primary))
            }
            val state = TextView(this).apply {
                text = if (custom == null) getString(R.string.theme_group_follow)
                else getString(R.string.theme_group_custom)
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@ThemeActivity, R.color.wg_text_secondary))
            }
            val follow = Button(this).apply {
                text = getString(R.string.theme_group_reset)
                textSize = 12f
                minHeight = dp(36f)
                minWidth = 0
                minimumWidth = 0
                isAllCaps = false
                setPadding(dp(10f), 0, dp(10f), 0)
                // 「跟随主题」按钮也用当前主题主色描边，跟上面的模式按钮保持一致
                background = buttonBg(false, GroupTheme.of(settings.themeId).palette(dark).primary)
                setTextColor(GroupTheme.of(settings.themeId).palette(dark).primary)
                visibility = if (custom == null) View.GONE else View.VISIBLE
                setOnClickListener { clearGroupColor(g.id) }
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { rightMargin = dp(10f) })
                addView(LinearLayout(this@ThemeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(state)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(follow)
            })

            // 候选色板：来自当前主题（保证和系统配色是一个色系）
            val swRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in swatches) {
                val v = View(this).apply {
                    background = circle(c)
                    setOnClickListener { setGroupColor(g.id, c) }
                }
                swRow.addView(v, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply {
                    rightMargin = dp(6f)
                    topMargin = dp(10f)
                })
            }
            card.addView(swRow)
            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) })
        }
        if (dark) { /* 分组卡片跟随 App 主题，预览色板已按模式取色 */ }
    }

    private fun setGroupColor(gid: String, color: Int) {
        val map = LinkedHashMap(settings.groupColors)
        map[gid] = color
        settings = settings.copy(groupColors = map)
        GroupStore.putSettings(this, settings)
        renderGroups()
        toastApplied()
    }

    private fun clearGroupColor(gid: String) {
        val map = LinkedHashMap(settings.groupColors)
        map.remove(gid)
        settings = settings.copy(groupColors = map)
        GroupStore.putSettings(this, settings)
        renderGroups()
        toastApplied()
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================
    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** 卡片底：选中时用主题主色描边（2dp），否则普通边框 */
    private fun cardBg(selected: Boolean, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14f).toFloat()
        setColor(ContextCompat.getColor(this@ThemeActivity, R.color.wg_surface))
        setStroke(
            if (selected) dp(2f) else dp(1f),
            if (selected) strokeColor else ContextCompat.getColor(this@ThemeActivity, R.color.wg_border)
        )
    }
}
