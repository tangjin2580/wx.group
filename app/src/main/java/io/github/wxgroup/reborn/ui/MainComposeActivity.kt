package io.github.wxgroup.reborn.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import io.github.wxgroup.reborn.ui.state.AppViewModel

/**
 * 设置界面入口（Compose 版）。
 *
 * 说明：旧的设置页是 XML + AppCompat 写的，这一版整体换成 Compose，
 * 目的只有一个 —— 把「模块有没有生效」这件事讲清楚，并顺手统一了
 * 「海盐薄荷」主题在模块 App 与微信进程里的表现。
 */
class MainComposeActivity : ComponentActivity() {

    private lateinit var vm: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容延伸到状态栏 / 导航栏下面（配合 Scaffold 的内边距使用）
        enableEdgeToEdge()

        vm = ViewModelProvider(this)[AppViewModel::class.java]

        setContent {
            AppRoot(vm = vm)
        }
    }
}
