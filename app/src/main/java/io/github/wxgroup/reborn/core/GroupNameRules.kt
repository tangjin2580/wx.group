package io.github.wxgroup.reborn.core

/**
 * 分组命名的统一规则（纯 Kotlin，不依赖 Android —— 便于单元测试）。
 *
 * ── 为什么集中在这里 ──
 * 以前三个入口各写一份判断：
 *   • GroupManageActivity：创建 / 重命名各自 `trim()` 后判空，且不合法时**静默什么都不做**
 *   • AppViewModel.renameGroup：`isBlank()` 直接 return，调用方无从知道失败了
 *   • 历史版本：直接拿「新分组」「已重命名」这种占位串当名字
 * 规则散落 + 失败静默，是「改了名字却没生效」这类问题的根因。现在只有一个入口。
 *
 * ── 规则（沿用既有行为，不新增限制）──
 *   • 提交前 trim()，去掉首尾空白
 *   • trim 后为空 → 不可提交（既有行为，原来只是没给反馈）
 *   • 长度上限 [MAX_LENGTH]（新增，防止长名称把行/对话框撑爆）
 *   • **不检查重名**：收纳组允许同名（微信侧也不禁止），不引入这条限制
 */
object GroupNameRules {

    /** 分组名最大长度（字符数，按 trim 后的结果算） */
    const val MAX_LENGTH = 24

    /** 校验结果 */
    enum class Error { NONE, BLANK, TOO_LONG }

    /** trim 之后的名字（唯一的规范化入口，所有入口都必须先过它） */
    fun normalize(raw: String?): String = (raw ?: "").trim()

    /** 校验 trim 后的名字；返回 [Error.NONE] 表示可提交 */
    fun check(raw: String?): Error {
        val name = normalize(raw)
        return when {
            name.isEmpty() -> Error.BLANK
            name.length > MAX_LENGTH -> Error.TOO_LONG
            else -> Error.NONE
        }
    }

    /** 是否可提交（[check] 的便捷写法） */
    fun isValid(raw: String?): Boolean = check(raw) == Error.NONE
}
