package io.github.wxgroup.reborn.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 微信风格的底部动作面板（ActionSheet）。
 *
 * 为什么不用 `android.app.AlertDialog`：那是系统/MIUI 的弹窗样式，字体、圆角、
 * 行高、分隔线全和微信不一样，在微信里弹出来一眼就能看出「这不是微信的东西」。
 * 这里用纯 View 搭一个和微信长按菜单同构的面板：
 *
 *   ┌──────────────────────────────┐
 *   │          分组「工作」          │   ← 14sp 灰色标题（可无）
 *   ├──────────────────────────────┤
 *   │           添加成员…           │   ← 17sp，56dp
 *   ├──────────────────────────────┤
 *   │            重命名             │
 *   ├──────────────────────────────┤
 *   │            删除分组           │   ← 危险项用品牌红
 *   └──────────────────────────────┘
 *              （8dp 间隙）
 *   ┌──────────────────────────────┐
 *   │             取消              │
 *   └──────────────────────────────┘
 *
 * 全部尺寸与微信 action sheet 对齐：条目 56dp、圆角 14dp、标题 14sp、条目 17sp。
 */
internal fun showWxActionSheet(
    act: Activity,
    title: String?,
    items: List<String>,
    destructiveIndex: Int = -1,
    onPick: (Int) -> Unit
) {
    val ctx = act
    val dark = isDarkContext(ctx)
    val sheetBg = if (dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
    val divider = if (dark) 0xFF3A3A3C.toInt() else 0xFFE5E5E5.toInt()
    val titleColor = if (dark) 0xFF8A8A8A.toInt() else 0xFF9A9A9A.toInt()
    val itemColor = if (dark) 0xFFD6D6D6.toInt() else 0xFF191919.toInt()
    val dangerColor = 0xFFFA5151.toInt()

    val dialog = Dialog(ctx)
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 12))
    }

    // ── 主面板 ─────────────────────────────────────────────────────────
    val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(sheetBg)
        }
    }
    if (!title.isNullOrBlank()) {
        panel.addView(TextView(ctx).apply {
            text = title
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(titleColor)
            setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14))
        })
        panel.addView(dividerView(ctx, divider))
    }
    items.forEachIndexed { i, label ->
        panel.addView(TextView(ctx).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(if (i == destructiveIndex) dangerColor else itemColor)
            isClickable = true
            background = ripple(dark)
            setOnClickListener {
                dialog.dismiss()
                onPick(i)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 56)))
        if (i != items.lastIndex) panel.addView(dividerView(ctx, divider))
    }
    root.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // ── 取消（独立一块，和微信一致）──────────────────────────────────────
    val cancel = TextView(ctx).apply {
        text = "取消"
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(itemColor)
        isClickable = true
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(sheetBg)
        }
        setOnClickListener { dialog.dismiss() }
    }
    root.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 56)).apply {
        topMargin = dp(ctx, 8)
    })

    dialog.setContentView(root)
    dialog.setCanceledOnTouchOutside(true)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.35f)
        // 避开底部手势条：用装饰视图的 padding 让面板整体上移
        decorView.setPadding(0, 0, 0, navigationBarHeight(ctx))
    }
    dialog.show()
}

private fun dividerView(ctx: Context, color: Int): View = View(ctx).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
}

/** 微信点击态：真正的 Ripple（深色用白色 20%，浅色用黑色 10%），不可点时也保持透明底 */
private fun ripple(dark: Boolean): android.graphics.drawable.Drawable {
    val c = if (dark) 0x33FFFFFF else 0x1A000000
    return android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(c), null, null
    )
}

internal fun isDarkContext(ctx: Context): Boolean = try {
    val mode = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
    mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
} catch (t: Throwable) {
    false
}

private fun dp(ctx: Context, v: Int): Int =
    android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

/** 底部手势条高度（拿不到就返回 0，不影响功能） */
private fun navigationBarHeight(ctx: Context): Int = try {
    val id = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
} catch (t: Throwable) {
    0
}
