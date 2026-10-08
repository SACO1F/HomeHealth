package com.example.homehealth.ui.navigation

import android.net.Uri

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
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

/**
 * 悬浮导航栏切换动效时长（毫秒）。
 * 滑动高亮块与各菜单项的配色**共用**这一条曲线：只让滑块动、颜色瞬间跳，
 * 会出现「目标项先瞬间高亮、滑块再滑过去」的割裂感。
 */
private const val NAV_SLIDE_MS = 500

/** 悬浮层统一圆角半径：底部导航栏、选中项高亮、问答悬浮输入栏共用，保证观感一致 */
internal val FLOATING_CORNER = 32.dp

/**
 * 需要避让悬浮导航栏的滚动列表的底部留白 = 悬浮栏高度 + 系统导航栏 inset（+ 额外余量）。
 *
 * NavHost 不再统一补导航栏 inset（补了内容就会在导航栏上方截断、露出底部背景条），
 * 所以列表要把「悬浮栏 + 系统导航栏」一起算进 contentPadding.bottom，末条才不会压在栏下。
 */
@Composable
internal fun floatingListBottomPadding(extra: Dp = 16.dp): Dp {
    val density = LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    return FLOATING_NAV_RESERVE + navBottom + extra
}

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
        // 因此在 NavHost 层补 statusBars padding（与旧外层 Scaffold 行为一致）。
        //
        // ⚠️ 刻意**不**在这里补 navigationBars padding：那样内容会在系统导航栏上方截断，
        // 导航栏 inset 区域只剩背景色，看起来就是底部的一条「白条 / 黑条」，做不到沉浸式。
        // 改为让内容一直铺到窗口底部（含系统导航栏区域），需要避让导航栏的贴底元素
        // （FAB、问答输入栏）各自用 Modifier.navigationBarsPadding() 处理。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 铺满整屏（含状态栏 / 导航栏 inset 区域）：edge-to-edge 下系统栏透明，
                // 若根容器不画背景，就会露出窗口背景色（浅色 #FAFDFB），
                // 深色模式下即表现为顶部与底部的两条白条。
                .background(MaterialTheme.colorScheme.background)
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.FAMILY,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
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

/** 悬浮透视底部导航：胶囊形容器 + 选中项整块高亮（**切换时高亮块滑动过去**），保留各模块主题色 */
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
        shape = RoundedCornerShape(FLOATING_CORNER),
        // 透视质感：高透表面色，内容从栏下穿过时隐约可见。
        // 刻意不加 border / tonalElevation —— 半透明表面上叠 1dp 描边与色调层，
        // 会在栏身中间渲染出一条细线（描边高光），去掉后才是干净的玻璃感。
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 12.dp
    ) {
        val gap = 4.dp
        val selectedIndex = items.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

        BoxWithConstraints(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            // 各项等宽（weight(1f)）+ 项间 gap，据此算出高亮块的位置与宽度
            val itemWidth = (maxWidth - gap * (items.size - 1)) / items.size
            val indicatorOffset by animateDpAsState(
                targetValue = (itemWidth + gap) * selectedIndex,
                animationSpec = tween(durationMillis = NAV_SLIDE_MS, easing = FastOutSlowInEasing),
                label = "navIndicatorOffset"
            )
            // 高亮色跟随选中模块，跨模块切换时颜色也平滑过渡
            val selectedModule = items.getOrNull(selectedIndex)?.module
            val indicatorColor by animateColorAsState(
                targetValue = selectedModule?.let {
                    (if (darkTheme) it.containerDark else it.containerLight).copy(alpha = 0.9f)
                } ?: Color.Transparent,
                animationSpec = tween(durationMillis = NAV_SLIDE_MS, easing = FastOutSlowInEasing),
                label = "navIndicatorColor"
            )

            // 滑动高亮块：覆盖「图标 + 文字」整块，浮在各项之下。
            // 外面必须套一层 matchParentSize 的 Box：直接给 fillMaxHeight 时，指示块会以
            // 「可用最大高度」报告自身尺寸，反过来把悬浮栏撑成整屏高（实测踩过）。
            // matchParentSize 的子项在父级尺寸确定后才测量、且不参与父级尺寸计算。
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(FLOATING_CORNER))
                        .background(indicatorColor)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                items.forEach { item ->
                    val selected = currentRoute == item.route
                    // 各模块用自身主题色：图标沿用原口径（未选中为淡化模块色）；文字未选中用中性色。
                    // 颜色也走与滑块相同的曲线 —— 否则目标项会「瞬间跳色」，滑块才慢慢滑过去。
                    val moduleColor = if (darkTheme) item.module.primaryDark
                    else item.module.primaryLight
                    val iconColor by animateColorAsState(
                        targetValue = if (selected) moduleColor else moduleColor.copy(alpha = 0.5f),
                        animationSpec = tween(durationMillis = NAV_SLIDE_MS, easing = FastOutSlowInEasing),
                        label = "navIconColor"
                    )
                    val labelColor by animateColorAsState(
                        targetValue = if (selected) moduleColor
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(durationMillis = NAV_SLIDE_MS, easing = FastOutSlowInEasing),
                        label = "navLabelColor"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(FLOATING_CORNER))
                            // 去掉按下时的灰色水波纹：它会在滑块之外先冒出一个灰色圆角块，
                            // 和「高亮块滑动」的反馈打架。切换反馈由滑块本身承担。
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onNavigate(item.route) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = stringResource(item.labelRes),
                            tint = iconColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(item.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = labelColor
                        )
                    }
                }
            }
        }
    }
}
