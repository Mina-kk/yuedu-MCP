package com.mina.legadostudio

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mina.legadostudio.ui.StudioApp

class MainActivity : ComponentActivity() {
    private var launchRoute by mutableStateOf<String?>(null)
    private var launchNonce by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边绘制并关闭系统对比度纱罩，保证状态栏/导航栏区域纯白不发灰
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        consume(intent)
        setContent { StudioApp(initialRoute = launchRoute, deepLinkNonce = launchNonce, onExit = { finish() }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        launchRoute = intent?.getStringExtra("route")
        // 消费后立即清除，避免 Activity 重建时重复导航到同一目标
        intent?.removeExtra("route")
        launchNonce++
    }
}
