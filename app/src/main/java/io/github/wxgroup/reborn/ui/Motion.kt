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

    /**
     * 一组正在跑的颜色动画。
     *
     * ── 为什么要它 ──
     * 以前 [animateColor] 每次调用都 `start()` 一个新 [ValueAnimator]，没人持有、
     * 没人取消。快速连点主题卡时，前一次的 5 个 animator 还在往同一批 View 里写旧颜色，
     * 于是出现「颜色先跳回上一个主题、再慢慢爬到新主题」的跳色；
     * Activity 已经 finish 了它们也还在跑（持有 View 引用，属于泄漏）。
     *
     * 用法：持有实例 → 开新动画前 [cancelAll] → [onDestroy] 里再 [cancelAll] 一次。
     */
    class ColorAnimators {
        private val running = ArrayList<ValueAnimator>()

        /** 启动一个颜色过渡；同时接管它的生命周期 */
        fun start(animator: ValueAnimator) {
            running.add(animator)
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    running.remove(animation)
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    running.remove(animation)
                }
            })
            animator.start()
        }

        /**
         * 取消全部在跑的动画（不再持有引用，也不会再往 View 里写颜色）。
         *
         * 先清空再逐个 cancel：`cancel()` 会回调上面的 onAnimationCancel，
         * 那个回调又要从 [running] 里 remove —— 边遍历边删会 ConcurrentModificationException。
         */
        fun cancelAll() {
            if (running.isEmpty()) return
            val snapshot = ArrayList(running)
            running.clear()
            snapshot.forEach { it.cancel() }
        }
    }

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
     * **不自己 start**：返回 animator 由调用方交给 [ColorAnimators] 管理，
     * 这样「开新动画前先取消旧的」和「onDestroy 清理」才有落点。
     * 系统开了「减少动效」时返回 null（调用方直接跳终值即可）。
     */
    fun colorAnimator(
        context: Context,
        from: Int,
        to: Int,
        onUpdate: (Int) -> Unit,
        duration: Long = DURATION_MS
    ): ValueAnimator? {
        if (from == to || reducedMotion(context)) {
            onUpdate(to)
            return null
        }
        return ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                onUpdate(GroupTheme.blend(from, to, t))
            }
        }
    }
}
