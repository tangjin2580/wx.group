package io.github.wxgroup.reborn.ui.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「LSPosed 是否真的生效」的判定测试。
 *
 * ── 为什么值得测 ──
 * 以前首页把「模块已安装 / 通道可用 / Hook 已跑起来」压成一个 moduleActive 布尔值，
 * 于是「App 一切正常但微信里毫无变化」这种最高频的误会根本无法在界面上表达。
 * 现在用 [HomeUiState.hookLastRunAt] 表示微信进程最后一次跑过 Hook 的时间，
 * 判定规则集中在一处（数据类里），这里把它钉住。
 */
class HomeStatusTest {

    @Test
    fun `从未跑过 Hook 视为未生效`() {
        val state = HomeUiState(hookLastRunAt = 0L)
        assertFalse(state.hookActive)
    }

    @Test
    fun `刚刚跑过 Hook 视为生效`() {
        val state = HomeUiState(hookLastRunAt = System.currentTimeMillis() - 60_000L)
        assertTrue(state.hookActive)
    }

    @Test
    fun `超过 30 天没再跑过视为已失效`() {
        val stale = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
        val state = HomeUiState(hookLastRunAt = stale)
        assertFalse(state.hookActive)
    }

    @Test
    fun `通道与 Hook 是两件事，互不影响`() {
        // 通道可用 ≠ 微信里生效：这正是要分开表达的原因
        val state = HomeUiState(channelReady = true, hookLastRunAt = 0L)
        assertTrue(state.channelReady)
        assertFalse(state.hookActive)
        // 反之亦然：微信进程写过日志，但镜像文件被删了
        val reversed = HomeUiState(channelReady = false, hookLastRunAt = System.currentTimeMillis())
        assertFalse(reversed.channelReady)
        assertTrue(reversed.hookActive)
    }

    @Test
    fun `列表状态：加载中与异常互斥，就绪才有数据`() {
        val loading = GroupsUiState()
        assertTrue(loading.loading)
        assertFalse(loading.groups.isNotEmpty())

        val failed = GroupsUiState(loading = false, error = "JSON 坏了")
        assertFalse(failed.loading)
        assertFalse(failed.groups.isNotEmpty())

        val ready = GroupsUiState(
            loading = false,
            groups = listOf(GroupUi(id = "a", name = "工作", icon = "★", iconTint = 0xFF000000))
        )
        assertFalse(ready.loading)
        assertTrue(ready.groups.size == 1)
    }
}
