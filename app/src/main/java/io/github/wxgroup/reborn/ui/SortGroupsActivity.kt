package io.github.wxgroup.reborn.ui

import android.annotation.SuppressLint
import android.animation.LayoutTransition
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.WxGroup

/**
 * 分组排序页 —— 交互照抄微信「通讯录 → 标签 → 标签排序」：
 * 每行右边一个 ☰ 拖拽把手，按住上下拖，松手即完成；点右下角「完成」保存。
 *
 * 为什么不在微信里直接拖：微信的会话列表是它自己的 ListView，触摸判定、position
 * 传递都被我们摸过一遍坑（见 wechat_signatures.json 里那两条铁律）。在这里拖动
 * 是我们自己的 View 层级，行为和顺序完全可控。微信侧只保留「上移 / 下移」做微调。
 */
class SortGroupsActivity : AppCompatActivity() {

    companion object {
        const val ACTION_SORT_GROUPS = "io.github.wxgroup.reborn.action.SORT_GROUPS"
        private const val ROW_HEIGHT_DP = 56
    }

    private lateinit var container: LinearLayout
    private val order = mutableListOf<WxGroup>()
    private var dragging: View? = null
    private var dragFrom = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "分组排序"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tip = TextView(this).apply {
            text = "按住右侧 ☰ 上下拖动调整顺序"
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(if (isDark()) Color.LTGRAY else Color.DKGRAY)
        }
        root.addView(tip, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 拖拽换位时，其它行靠 LayoutTransition 的 CHANGING 平滑让位；
        // 被拖的那一行由我们手动 translationY 跟手，所以关掉它自己的 APPEAR/DISAPPEAR 动画，
        // 避免 removeView/addView 时闪烁。
        container.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(LayoutTransition.CHANGING, 150)
            setStartDelay(LayoutTransition.CHANGING, 0)
            disableTransitionType(LayoutTransition.APPEARING)
            disableTransitionType(LayoutTransition.DISAPPEARING)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        val done = Button(this).apply {
            text = "完成"
            setOnClickListener { save() }
        }
        root.addView(done, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        setContentView(root)

        rebuild()
    }

    override fun onResume() {
        super.onResume()
        // 每次进来都从镜像拉一次最新的（微信里刚建的/刚收编的要能看到）
        GroupStore.importFromTmp(this)
        rebuild()
    }

    private fun rebuild() {
        order.clear()
        order.addAll(GroupStore.sortForDisplay(GroupStore.getGroups(this)))
        container.removeAllViews()
        if (order.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有分组"
                setPadding(dp(16), dp(24), dp(16), dp(24))
            })
            return
        }
        order.forEachIndexed { i, g -> container.addView(buildRow(i, g)) }
    }

    private fun buildRow(index: Int, g: WxGroup): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (isDark()) Color.parseColor("#22FFFFFF") else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)
            )
        }
        val label = TextView(this).apply {
            text = "${index + 1}. ${g.name}"
            textSize = 16f
            setPadding(dp(16), 0, 0, 0)
            setTextColor(if (isDark()) Color.WHITE else Color.BLACK)
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val count = TextView(this).apply {
            text = "${g.members.size} 人"
            textSize = 13f
            setPadding(0, 0, dp(8), 0)
            setTextColor(Color.GRAY)
        }
        row.addView(count)

        val handle = TextView(this).apply {
            text = "☰"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(if (isDark()) Color.LTGRAY else Color.DKGRAY)
            // 拖拽区做大一点，手指好按
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT)
            setOnTouchListener { v, e -> onHandleTouch(v, e) }
        }
        row.addView(handle)

        return row
    }

    /**
     * 把手上的拖动。
     *
     * 拖动的行平滑「跟手」（用 translationY 让行中心一直贴住手指），跨槽位时
     * 先 removeView/addView 换位（其它行由 LayoutTransition 的 CHANGING 动画让位），
     * 再用 translationY 抵消容器里的位置变化，视觉上无跳变。
     */
    private fun onHandleTouch(v: View, e: MotionEvent): Boolean {
        val row = v.parent as? View ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = row
                dragFrom = container.indexOfChild(row)
                // 关键：不让外层 ScrollView 抢走竖直拖动，否则一拖就变成滚列表
                row.parent?.requestDisallowInterceptTouchEvent(true)
                row.setBackgroundColor(if (isDark()) Color.parseColor("#44FFFFFF") else Color.parseColor("#E8E8E8"))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging !== row) return false
                val rowH = rowHeight()
                val target = indexForY(e.rawY)
                val from = container.indexOfChild(row)
                // 1) 跨槽位就换位（数据 + 视图一起挪）
                if (target != from) {
                    container.removeViewAt(from)
                    container.addView(row, target)
                    reorderData(from, target)
                    bumpLabels()
                }
                // 2) 让行中心贴住手指：目标槽位中心 + 手指相对中心线的偏移
                val loc = IntArray(2)
                container.getLocationOnScreen(loc)
                val slotCenter = loc[1] + target * rowH + rowH / 2
                row.translationY = e.rawY - slotCenter
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging === row) {
                    dragging = null
                    row.parent?.requestDisallowInterceptTouchEvent(false)
                    row.setBackgroundColor(if (isDark()) Color.parseColor("#22FFFFFF") else Color.WHITE)
                    // 松手：平滑滑回槽位
                    row.animate().translationY(0f).setDuration(150).start()
                }
                return true
            }
        }
        return false
    }

    /** 行高：优先取第一行的实测高度，避免不同 density 下 dp 换算有偏差 */
    private fun rowHeight(): Int =
        (container.getChildAt(0)?.height ?: dp(ROW_HEIGHT_DP)).coerceAtLeast(1)

    /**
     * rawY 落在第几行。
     *
     * ⚠️ 这里**不能**去问每个子 View 的 getLocationOnScreen()：刚 removeView/addView 之后
     * 还没走过 layout，子 View 的坐标是旧的，同一个拖动会被反复判成"换行"，
     * 行就在两个位置之间来回弹（实测拖动结果和落点对不上）。
     * 容器自己的位置在拖动过程中是稳定的，行高也是固定的，直接算最稳。
     */
    private fun indexForY(rawY: Float): Int {
        val n = container.childCount
        if (n == 0) return -1
        val loc = IntArray(2)
        container.getLocationOnScreen(loc)
        val rowH = dp(ROW_HEIGHT_DP).coerceAtLeast(1)
        val rel = rawY - loc[1]
        if (rel < 0) return 0
        return (rel / rowH).toInt().coerceIn(0, n - 1)
    }

    private fun reorderData(from: Int, to: Int) {
        if (from !in order.indices || to !in order.indices) return
        val g = order.removeAt(from)
        order.add(to, g)
    }

    /** 序号跟着挪，用户能看到 1./2./3. 在变 */
    private fun bumpLabels() {
        for (i in 0 until container.childCount) {
            val rowView = container.getChildAt(i) as? ViewGroup ?: continue
            val label = rowView.getChildAt(0) as? TextView ?: continue
            val g = order.getOrNull(i) ?: continue
            label.text = "${i + 1}. ${g.name}"
        }
    }

    private fun save() {
        if (order.isEmpty()) {
            finish()
            return
        }
        order.forEachIndexed { i, g -> g.order = i + 1 }
        GroupStore.putGroups(this, order)
        Toast.makeText(this, "已保存分组顺序", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun isDark(): Boolean {
        val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
