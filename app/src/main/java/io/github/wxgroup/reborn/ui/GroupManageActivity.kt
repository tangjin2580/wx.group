package io.github.wxgroup.reborn.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.WxGroup

class GroupManageActivity : AppCompatActivity() {

    companion object {
        const val ACTION_ADD_TO_GROUP = "io.github.wxgroup.reborn.action.ADD_TO_GROUP"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_GROUP_ID = "group_id"
        private const val PREFIX = "grp_"
        private const val MENU_SORT = 1
    }

    private lateinit var listView: ListView
    private lateinit var hint: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private var groups = listOf<WxGroup>()
    private var pendingUsername: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_groups)
        title = getString(R.string.group_manage_title)

        listView = findViewById(R.id.list_groups)
        hint = findViewById(R.id.tv_hint)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, pos, _ -> editGroup(groups[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            editGroup(groups[pos]); true
        }

        findViewById<Button>(R.id.btn_add).setOnClickListener { createGroup() }

        // 重要：必须先 refresh() 把 groups 读进来，再处理「添加到分组」意图——
        // 否则弹窗里的可选分组是空列表，只能「新建分组」，无法加进已有分组。
        refresh()

        // 从微信长按菜单跳转：选择把某个会话加入哪个分组
        if (intent?.action == ACTION_ADD_TO_GROUP) {
            pendingUsername = intent.getStringExtra(EXTRA_USERNAME)
            showAddToGroupDialog()
        }

        // 从微信长按收纳组头部跳转：直接打开该分组的编辑界面
        intent?.getStringExtra(EXTRA_GROUP_ID)?.let { gid ->
            val g = groups.firstOrNull { it.id == gid }
            if (g != null) editGroup(g)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        // 先跟镜像同步一次：微信里刚建/刚收编的分组要能立刻看到（镜像由微信进程写）
        GroupStore.importFromTmp(this)
        groups = GroupStore.sortForDisplay(GroupStore.getGroups(this))
        adapter.clear()
        adapter.addAll(groups.map {
            "${it.name}  (${it.members.size}人)${if (it.pinned) " · 置顶" else ""}${if (it.muted) " · 免打扰" else ""}"
        })
        hint.text = if (groups.isEmpty()) getString(R.string.group_empty) else ""
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SORT, 0, "排序分组").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_SORT) {
            startActivity(Intent(this, SortGroupsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun createGroup(username: String? = null) {
        val input = EditText(this).apply { hint = getString(R.string.group_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.group_add)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    // id 用十进制毫秒时间戳，和微信侧建的分组保持一致 ——
                    // GroupStore.orderKey() 就是靠这个从 id 推出创建顺序的
                    val id = PREFIX + System.currentTimeMillis()
                    val nextOrder = (groups.maxOfOrNull { it.order } ?: 0) + 1
                    GroupStore.putGroup(this, WxGroup(id, name, order = nextOrder))
                    username?.let { GroupStore.addMember(this, id, it) }
                    if (username != null) {
                        Toast.makeText(this, "已新建「$name」并加入", Toast.LENGTH_SHORT).show()
                        finish()
                    } else refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editGroup(group: WxGroup) {
        val actions = arrayOf(
            getString(R.string.group_rename),
            getString(R.string.group_pin),
            getString(R.string.group_mute),
            getString(R.string.group_members),
            getString(R.string.group_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(group.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> renameGroup(group)
                    1 -> { group.pinned = !group.pinned; GroupStore.putGroup(this, group); refresh() }
                    2 -> { group.muted = !group.muted; GroupStore.putGroup(this, group); refresh() }
                    3 -> manageMembers(group)
                    4 -> AlertDialog.Builder(this)
                        .setTitle(R.string.group_delete)
                        .setMessage("确定删除「${group.name}」？")
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            GroupStore.removeGroup(this, group.id); refresh()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun manageMembers(group: WxGroup) {
        val members = group.members.toList().toTypedArray()
        if (members.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(group.name)
                .setMessage(getString(R.string.group_members_empty))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.group_members_title, members.size))
            .setMessage(getString(R.string.group_members_remove_hint))
            .setItems(members) { _, which ->
                GroupStore.removeMember(this, group.id, members[which])
                refresh()
                manageMembers(group)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameGroup(group: WxGroup) {
        val input = EditText(this).apply { setText(group.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.group_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { group.name = name; GroupStore.putGroup(this, group); refresh() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddToGroupDialog() {
        val username = pendingUsername ?: return
        val names = groups.map { it.name }.toMutableList().apply { add("（新建分组）") }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("将 $username 加入分组")
            .setItems(names) { _, which ->
                if (which == groups.size) {
                    createGroup(username)   // 新建分组并直接把当前会话加进去，内部确认后 finish
                } else {
                    val g = groups[which]
                    GroupStore.addMember(this, g.id, username)
                    Toast.makeText(this, "已加入「${g.name}」", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }
}
