package io.github.wxgroup.reborn.ui.util

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 系统「移除动画 / 动画时长缩放」设置的 Compose 读取口。
 *
 * Motion 那边的 [io.github.wxgroup.reborn.ui.Motion.reducedMotion] 是给 View 体系用的
 * （要传 Context）；Compose 侧统一走这里，避免在 Composable 背后反复读 ContentResolver。
 *
 * 开了”移除动画”时必须真的「不动」——不是把时长缩短一点，而是直接跳终值。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val scale = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }
    return scale <= 0f
}
