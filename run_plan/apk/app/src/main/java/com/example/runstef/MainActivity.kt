package com.example.runstef

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
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
import com.example.runstef.ui.auth.AuthViewModel
import com.example.runstef.ui.auth.LockScreen
import com.example.runstef.ui.export.ExportScreen
import com.example.runstef.ui.home.HomeScreen
import com.example.runstef.ui.home.ToolWebViewScreen
import com.example.runstef.ui.home.calculatorTools
import com.example.runstef.ui.plans.PlanViewScreen
import com.example.runstef.ui.plans.PlansScreen
import com.example.runstef.ui.security.SecurityScreen
import com.example.runstef.ui.theme.RunstefTheme

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
        // Открываем вкладку «Мои планы» по умолчанию, если есть хотя бы один сохранённый план.
        val hasPlans = PlanRepository(this).listPlans().isNotEmpty()
        setContent {
            RunstefTheme {
                val authViewModel: AuthViewModel = viewModel()
                RunstefApp(
                    activity = this,
                    authViewModel = authViewModel,
                    startDestination = if (hasPlans) Dest.Plans.route else Dest.Home.route
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunstefApp(activity: FragmentActivity, authViewModel: AuthViewModel, startDestination: String) {
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
                            Icon(Icons.Filled.Lock, contentDescription = "Безопасность")
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
                HomeScreen(onOpenTool = { tool ->
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
                        val planTool = calculatorTools.first { it.id == "plan" }
                        navController.navigate("tool/${Uri.encode(planTool.url)}")
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
                SecurityScreen(authViewModel = authViewModel, onLockNow = { authViewModel.lockNow() })
            }
        }
    }
}
