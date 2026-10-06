package com.example.homehealth.ui.navigation

import android.net.Uri

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.example.homehealth.R
import com.example.homehealth.ui.screens.alerts.AlertsScreen
import com.example.homehealth.ui.screens.documentupload.DocumentUploadScreen
import com.example.homehealth.ui.screens.familylist.FamilyListScreen
import com.example.homehealth.ui.screens.memberdetail.MemberDetailScreen
import com.example.homehealth.ui.screens.qa.QAScreen
import com.example.homehealth.ui.screens.recorddetail.RecordDetailScreen
import com.example.homehealth.ui.screens.reminders.RemindersScreen
import com.example.homehealth.ui.screens.settings.SettingsScreen
import com.example.homehealth.ui.theme.ModuleTheme
import com.example.homehealth.ui.theme.ModuleThemedTheme

/** 路由定义 */
object Routes {
    const val FAMILY = "family"
    const val ALERTS = "alerts"
    const val REMINDERS = "reminders"
    const val QA = "qa"
    const val SETTINGS = "settings"
    const val MEMBER = "member/{memberId}"
    const val RECORD = "member/{memberId}/record/{type}"
    const val UPLOAD = "upload/{memberId}"

    fun member(memberId: String) = "member/${Uri.encode(memberId)}"
    fun record(memberId: String, type: String) =
        "member/${Uri.encode(memberId)}/record/${Uri.encode(type)}"
    fun upload(memberId: String) = "upload/${Uri.encode(memberId)}"
}

/** 顶层页共用导航规则；返回家庭时直接回到列表，避免恢复成员页的旧导航栈。 */
fun NavHostController.navigateToTopLevel(route: String) {
    if (route == Routes.FAMILY && popBackStack(Routes.FAMILY, inclusive = false)) return
    navigate(route) {
        popUpTo(Routes.FAMILY) { saveState = route != Routes.FAMILY }
        launchSingleTop = true
        restoreState = route != Routes.FAMILY
    }
}

private data class BottomItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val module: ModuleTheme
)

/** 悬浮导航栏占用的底部高度（栏高 66 + 底边距 10，向上取整）——贴底操作栏的避让预留 */
internal val FLOATING_NAV_RESERVE = 80.dp

/** 应用根导航：底部导航（各模块独立主题色）+ NavHost（按当前模块切换主题） */
@Composable
fun RootApp(navController: NavHostController, darkTheme: Boolean) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val bottomItems = listOf(
        BottomItem(Routes.FAMILY, R.string.nav_family, Icons.Filled.Home, ModuleTheme.FAMILY),
        BottomItem(Routes.ALERTS, R.string.nav_alerts, Icons.Filled.Notifications, ModuleTheme.ALERTS),
        BottomItem(Routes.REMINDERS, R.string.nav_reminders, Icons.Filled.Alarm, ModuleTheme.REMINDERS),
        BottomItem(Routes.QA, R.string.nav_qa, Icons.Filled.QuestionAnswer, ModuleTheme.QA),
        BottomItem(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings, ModuleTheme.SETTINGS)
    )
    val showBottomBar = currentRoute in bottomItems.map { it.route }

    // 键盘弹出时隐藏底部导航：问答输入栏需要贴合键盘，导航栏被键盘盖住毫无意义，
    // 且隐藏后外层 content padding 收缩，配合 imePadding/consumeWindowInsets 输入栏才能贴平键盘上沿
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // 当前模块：底部页取自身模块；二级页（成员/记录/上传）归入家庭模块
    val currentModule = when {
        currentRoute == Routes.ALERTS -> ModuleTheme.ALERTS
        currentRoute == Routes.REMINDERS -> ModuleTheme.REMINDERS
        currentRoute == Routes.QA -> ModuleTheme.QA
        currentRoute == Routes.SETTINGS -> ModuleTheme.SETTINGS
        else -> ModuleTheme.FAMILY
    }

    ModuleThemedTheme(module = currentModule, darkTheme = darkTheme) {
        // 悬浮透视底部栏：列表内容从半透明栏下方穿过，营造玻璃透视质感。
        // 各页面已把 contentWindowInsets 置零、统一依赖外层处理系统栏 inset —— 
        // 因此在 NavHost 层补 statusBars / navigationBars padding（与旧外层 Scaffold 行为一致），
        // 悬浮栏浮于导航栏 inset 之上，列表底部留白（96dp）保证末条可见。
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.FAMILY,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                composable(Routes.FAMILY) {
                    FamilyListScreen(navController)
                }
                composable(
                    Routes.MEMBER,
                    arguments = listOf(navArgument("memberId") { type = NavType.StringType })
                ) {
                    MemberDetailScreen(navController)
                }
                composable(
                    Routes.RECORD,
                    arguments = listOf(
                        navArgument("memberId") { type = NavType.StringType },
                        navArgument("type") { type = NavType.StringType }
                    )
                ) {
                    RecordDetailScreen(navController)
                }
                composable(
                    Routes.UPLOAD,
                    arguments = listOf(navArgument("memberId") { type = NavType.StringType })
                ) {
                    DocumentUploadScreen(navController)
                }
                composable(Routes.ALERTS) {
                    AlertsScreen(navController)
                }
                composable(Routes.REMINDERS) {
                    RemindersScreen(navController)
                }
                composable(Routes.QA) {
                    QAScreen(navController)
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(navController)
                }
            }

            // 悬浮透视导航栏：半透明表面 + 细描边 + 阴影，浮于内容之上
            if (showBottomBar && !imeVisible) {
                FloatingBottomBar(
                    items = bottomItems,
                    currentRoute = currentRoute,
                    darkTheme = darkTheme,
                    onNavigate = { route ->
                        if (currentRoute != route) navController.navigateToTopLevel(route)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 10.dp)
                )
            }
        }
    }
}

/** 悬浮透视底部导航：胶囊形容器 + 每项独立圆形指示区，保留各模块主题色 */
@Composable
private fun FloatingBottomBar(
    items: List<BottomItem>,
    currentRoute: String?,
    darkTheme: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        // 透视质感：高透表面色，内容从栏下穿过时隐约可见
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        shadowElevation = 12.dp,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                // 各模块用自身主题色：选中纯色，未选中淡化（与旧 NavigationBar 口径一致）
                val moduleColor = if (darkTheme) item.module.primaryDark
                else item.module.primaryLight
                val containerColor = if (darkTheme) item.module.containerDark
                else item.module.containerLight
                val itemColor = if (selected) moduleColor
                else moduleColor.copy(alpha = 0.5f)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onNavigate(item.route) }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) containerColor.copy(alpha = 0.9f)
                                else Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = stringResource(item.labelRes),
                            tint = itemColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(item.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = if (selected) itemColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
