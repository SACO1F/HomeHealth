package com.example.homehealth

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.navigation.compose.rememberNavController
import com.example.homehealth.data.SettingsPrefs
import com.example.homehealth.ui.navigation.RootApp
import com.example.homehealth.ui.screens.consent.ConsentScreen
import com.example.homehealth.ui.theme.HomeHealthTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settingsPrefs: SettingsPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyStoredLanguage()
        enableEdgeToEdge()
        setContent {
            // 外观模式：跟随系统 / 浅色 / 深色（设置页切换后即时生效）
            val themeMode by settingsPrefs.themeModeFlow.collectAsState()
            val darkTheme = when (themeMode) {
                SettingsPrefs.THEME_LIGHT -> false
                SettingsPrefs.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            // 状态栏/导航栏图标的明暗必须跟随「应用内主题」而非系统主题：
            // enableEdgeToEdge 的 auto 样式默认按系统深浅色判断，当设置页强制的
            // 外观模式与系统不一致时，状态栏的时间/信号会与背景同色（不可见）。
            // 按当前生效主题刷新系统栏样式；darkTheme 变化（切外观模式）时自动重刷。
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.Transparent.toArgb(), Color.Transparent.toArgb()
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.Transparent.toArgb(), Color.Transparent.toArgb()
                    ) { darkTheme }
                )
            }
            HomeHealthTheme(darkTheme = darkTheme) {
                // 首启必须主动同意「隐私与免责说明」：未同意时不进入主界面，也不申请任何权限
                // （把权限申请也挡在同意之前 —— 先要权限再给说明，顺序是反的）
                var consented by remember { mutableStateOf(settingsPrefs.hasAcceptedConsent) }
                if (consented) {
                    val navController = rememberNavController()
                    RequestNotificationPermissionOnce()
                    RootApp(navController = navController, darkTheme = darkTheme)
                } else {
                    ConsentScreen(
                        onAccept = {
                            settingsPrefs.acceptConsent()
                            consented = true
                        },
                        // 不同意即退出：SplashActivity 已出栈，finish 后应用关闭
                        onDecline = { finish() }
                    )
                }
            }
        }
    }

    /**
     * 启动时应用存储的语言偏好（API 33+ 系统会自动持久化 per-app locale，
     * 低版本由本方法在每次启动时恢复）。
     */
    private fun applyStoredLanguage() {
        val current = AppCompatDelegate.getApplicationLocales()
        if (!current.isEmpty) return
        when (settingsPrefs.languageMode) {
            SettingsPrefs.LANGUAGE_ZH ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh"))
            SettingsPrefs.LANGUAGE_EN ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
    }
}

/** Android 13+ 请求通知权限（仅一次） */
@Composable
private fun RequestNotificationPermissionOnce() {
    val context = LocalContext.current
    var requested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (!requested &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
