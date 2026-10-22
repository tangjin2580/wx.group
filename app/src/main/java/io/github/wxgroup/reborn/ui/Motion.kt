package io.github.wxgroup.reborn.ui

import android.content.Context
import android.provider.Settings

/**
 * 动效开关：读系统的「动画时长缩放」（开发者选项里的「动画程序时长调整」/
 * 无障碍里的「移除动画」）。被调成 0 说明用户不想看动效，我们就只做瞬时的状态切换。
 *
 * 只保留一个入口，避免以后哪个页面又偷偷加上硬编码的动画。
 */
object Motion {

    /** 与 @integer/wg_motion_ms 对齐的过渡时长（180–250ms 区间） */
    const val DURATION_MS = 200L

    fun reducedMotion(context: Context): Boolean {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        return scale <= 0f
    }
}
