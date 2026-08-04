package com.example.runstef

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.example.runstef.data.PlanRepository
import com.example.runstef.data.VersionCompare
import com.example.runstef.ui.auth.AuthViewModel
import com.example.runstef.ui.auth.LockScreen
import com.example.runstef.ui.export.ExportScreen
import com.example.runstef.ui.home.DEFAULT_PLAN_URL
import com.example.runstef.ui.home.HomeScreen
import com.example.runstef.ui.home.HomeViewModel
import com.example.runstef.ui.home.ToolWebViewScreen
import com.example.runstef.ui.plans.PlanViewScreen
import com.example.runstef.ui.plans.PlansScreen
import com.example.runstef.ui.security.SecurityScreen
import com.example.runstef.ui.theme.RunstefTheme
import com.example.runstef.ui.update.UpdateAvailableDialog

private sealed class Dest(val route: String, val label: String) {
    data object Home : Dest("home", "Главная")
    data object Plans : Dest("plans", "Мои планы")
    data object Export : Dest("export", "Экспорт")
}

private val bottomDestinations = listOf(Dest.Home, Dest.Plans, Dest.Export)

/**
 * Наследуется от FragmentActivity (а не ComponentActivity), т.к. androidx.biometric.BiometricPrompt
 * требует FragmentActivity/Fragment для показа системного диалога биометрии — см. ui/auth/LockScreen.
 *
 * ПИН/биометрия защищают только вкладку «Экспорт» (там лежат токены Garmin/intervals.icu) —
 * остальное приложение (калькуляторы, мои планы) доступно без разблокировки.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Должен вызываться до super.onCreate — иначе тема Theme.Runstef.Starting
        // не подхватится и система покажет свой автогенерированный (обрезающий иконку) сплэш.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Только для debug-сборки (android:debuggable) — позволяет открыть chrome://inspect
        // на компьютере и посмотреть консоль/DOM WebView прямо на реальном устройстве, где
        // поведение (в т.ч. кнопка «К текущему дню» в плане) может отличаться от эмулятора.
        // В release-сборке debuggable=false, поэтому здесь ничего не включится.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        // Открываем вкладку «Мои планы» по умолчанию, если есть хотя бы один сохранённый план.
        val hasPlans = PlanRepository(this).listPlans().isNotEmpty()
        setContent {
            RunstefTheme {
                val authViewModel: AuthViewModel = viewModel()
                val homeViewModel: HomeViewModel = viewModel()
                RunstefApp(
                    activity = this,
                    authViewModel = authViewModel,
                    homeViewModel = homeViewModel,
                    startDestination = if (hasPlans) Dest.Plans.route else Dest.Home.route
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunstefApp(
    activity: FragmentActivity,
    authViewModel: AuthViewModel,
    homeViewModel: HomeViewModel,
    startDestination: String
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    // На WebView-экранах (калькуляторы, просмотр плана) верхний тулбар скрываем,
    // чтобы оставить только нижнюю навигацию и сэкономить место на экране.
    val hideTopBar = currentRoute?.hierarchy?.any {
        it.route == "tool/{url}" || it.route == "planview?path={path}"
    } == true

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // На WebView-экранах (hideTopBar==true) topBar ничего не рисует, но Scaffold по умолчанию
        // всё равно резервирует отступ под системные бары в contentWindowInsets. Из-за этого вместе
        // со вторым Scaffold внутри ToolWebViewScreen отступ под статус-бар учитывался дважды и
        // сверху экрана калькулятора оставалась пустая полоса. Инсеты обрабатываем только здесь.
        contentWindowInsets = if (hideTopBar) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!hideTopBar) {
                TopAppBar(
                    title = { Text("Runstef") },
                    actions = {
                        IconButton(onClick = { navController.navigate("security") }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                        }
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { dest ->
                    val selected = currentRoute?.hierarchy?.any {
                        it.route == dest.route || it.route?.startsWith("${dest.route}?") == true
                    } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            // Без saveState/restoreState намеренно: клик по нижнему меню всегда
                            // должен вести в корень вкладки, а не восстанавливать вложенный экран
                            // (например «Главная» → калькулятор → «Главная» снова открывает список,
                            // а не сам калькулятор).
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id)
                                launchSingleTop = true
                            }
                        },
                        icon = {
                            when (dest) {
                                Dest.Home -> Icon(Icons.Filled.Home, contentDescription = dest.label)
                                Dest.Plans -> Icon(Icons.AutoMirrored.Filled.List, contentDescription = dest.label)
                                Dest.Export -> Icon(Icons.Filled.UploadFile, contentDescription = dest.label)
                            }
                        },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding).fillMaxSize()
        ) {
            composable(Dest.Home.route) {
                val homeState by homeViewModel.uiState.collectAsState()
                HomeScreen(state = homeState, onOpenTool = { tool ->
                    navController.navigate("tool/${Uri.encode(tool.url)}")
                })
            }
            composable(
                route = "tool/{url}",
                arguments = listOf(navArgument("url") { type = NavType.StringType })
            ) { entry ->
                val url = Uri.decode(entry.arguments?.getString("url") ?: "")
                ToolWebViewScreen(url = url)
            }
            composable(Dest.Plans.route) {
                PlansScreen(
                    onExportPlan = { filePath ->
                        navController.navigate("export?plan=${Uri.encode(filePath)}")
                    },
                    onViewPlan = { filePath ->
                        navController.navigate("planview?path=${Uri.encode(filePath)}")
                    },
                    onCreatePlan = {
                        // Список инструментов приходит из конфига (см. HomeViewModel) — если он
                        // ещё не загрузился или временно не содержит пункт "plan", используем
                        // тот же fallback-URL, что и во встроенном в apk конфиге.
                        val planUrl = homeViewModel.uiState.value.tools
                            .firstOrNull { it.id == "plan" }?.url
                            ?: DEFAULT_PLAN_URL
                        navController.navigate("tool/${Uri.encode(planUrl)}")
                    },
                    onOpenUrl = { url ->
                        navController.navigate("tool/${Uri.encode(url)}")
                    }
                )
            }
            composable(
                route = "planview?path={path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { entry ->
                val path = Uri.decode(entry.arguments?.getString("path") ?: "")
                PlanViewScreen(filePath = path)
            }
            composable(
                route = "export?plan={plan}",
                arguments = listOf(navArgument("plan") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { entry ->
                val plan = entry.arguments?.getString("plan")
                val unlocked by authViewModel.unlocked.collectAsState()
                // Разблокировка сбрасывается при выходе с вкладки — при следующем открытии «Экспорта»
                // снова нужно ввести ПИН/биометрию.
                DisposableEffect(Unit) {
                    onDispose { authViewModel.lockNow() }
                }
                if (unlocked) {
                    ExportScreen(preselectedFilePath = plan)
                } else {
                    LockScreen(activity = activity, authViewModel = authViewModel)
                }
            }
            composable("security") {
                SecurityScreen(
                    authViewModel = authViewModel,
                    homeViewModel = homeViewModel,
                    onLockNow = { authViewModel.lockNow() }
                )
            }
        }
    }

    // Диалог автообновления — показывается поверх любого экрана (не привязан к конкретному
    // маршруту), пока не отклонён, не пропущен либо не запущена установка. «Позже» живёт только
    // в памяти текущего процесса, «Пропустить эту версию» — сохраняется на диск (см. HomeViewModel).
    val effectiveConfig by homeViewModel.effectiveConfig.collectAsState()
    val updateDismissed by homeViewModel.updateDismissed.collectAsState()
    val skippedVersion by homeViewModel.skippedVersion.collectAsState()
    val update = effectiveConfig?.update
    val ownVersion = effectiveConfig?.ownVersion
    if (update != null && ownVersion != null && !updateDismissed && update.latestVersion != skippedVersion &&
        VersionCompare.isNewer(update.latestVersion, ownVersion)
    ) {
        UpdateAvailableDialog(
            latestVersion = update.latestVersion,
            apkUrl = update.apkUrl,
            onDismiss = { homeViewModel.dismissUpdate() },
            onSkip = { homeViewModel.skipVersion(update.latestVersion) }
        )
    }
}
