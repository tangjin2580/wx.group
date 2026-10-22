package io.github.wxgroup.reborn.ui

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import io.github.wxgroup.reborn.core.GroupTheme

/**
 * 动效开关：读系统的「动画时长缩放」（开发者选项里的「动画程序时长调整」/
 * 无障碍里的「移除动画」）。被调成 0 说明用户不想看动效，我们就只做瞬时的状态切换。
 *
 * 只保留一个入口，避免以后哪个页面又偷偷加上硬编码的动画。
 */
object Motion {

    /** 与 @integer/wg_motion_ms 对齐的过渡时长（180–250ms 区间） */
    const val DURATION_MS = 200L
    /** 主题切换的过渡时长（200–300ms 区间） */
    const val THEME_MS = 240L

    fun reducedMotion(context: Context): Boolean {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        return scale <= 0f
    }

    /**
     * 颜色过渡：200ms（主题切换用 240ms，见 [THEME_MS]）从 [from] 混到 [to]，
     * 每帧把中间色交给 [onUpdate] 去刷新具体的 View。
     *
     * 只有一个 animator、不 new 一堆对象：切换主题时最多几张卡片在动，
     * 全程就是一次 blend 计算。系统开了「减少动效」时直接跳到终值。
     */
    fun animateColor(
        context: Context,
        from: Int,
        to: Int,
        onUpdate: (Int) -> Unit,
        duration: Long = DURATION_MS
    ) {
        if (from == to || reducedMotion(context)) {
            onUpdate(to)
            return
        }
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = duration
        va.interpolator = DecelerateInterpolator()
        va.addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            onUpdate(GroupTheme.blend(from, to, t))
        }
        va.start()
    }
}
