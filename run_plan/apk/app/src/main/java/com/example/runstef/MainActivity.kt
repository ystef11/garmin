package com.example.runstef

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.runstef.data.VersionCompare
import com.example.runstef.network.garmin.GarminTokenStore
import com.example.runstef.ui.analytics.AnalyticsReportScreen
import com.example.runstef.ui.analytics.AnalyticsScreen
import com.example.runstef.ui.auth.AuthViewModel
import com.example.runstef.ui.auth.LockScreen
import com.example.runstef.ui.export.ExportScreen
import com.example.runstef.ui.home.DEFAULT_PLAN_URL
import com.example.runstef.ui.home.HomeScreen
import com.example.runstef.ui.home.HomeViewModel
import com.example.runstef.ui.home.ToolUrlBuilder
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
    data object Analytics : Dest("analytics", "Аналитика")
}

private val bottomDestinations = listOf(Dest.Home, Dest.Plans, Dest.Export, Dest.Analytics)

/**
 * Наследуется от FragmentActivity (а не ComponentActivity), т.к. androidx.biometric.BiometricPrompt
 * требует FragmentActivity/Fragment для показа системного диалога биометрии — см. ui/auth/LockScreen.
 *
 * ПИН/биометрия закрывают ВСЁ приложение целиком (а не только вкладку «Экспорт»), но только если
 * есть хотя бы один сохранённый аккаунт Garmin (см. GarminTokenStore.savedAccounts) — там лежат
 * токены Garmin/intervals.icu, и именно их наличие включает защиту. Если аккаунтов ещё нет —
 * приложение открывается сразу, без ПИН-экрана. Разблокировка живёт, пока жив процесс
 * приложения — при возврате из фона повторно не запрашивается, только при первом запуске
 * (или после того, как систему убила процесс) и по кнопке "Заблокировать" — см. RunstefApp.
 */
class MainActivity : FragmentActivity() {

    // ИСПРАВЛЕНО (ревью п.16, таблица "POST_NOTIFICATIONS"): разрешение объявлено в манифесте,
    // но на Android 13+ (API 33, targetSdk тут 36) это dangerous-разрешение — без runtime-
    // запроса система молча не показывает уведомления foreground-сервиса (AnalyticsImportService
    // — прогресс импорта/экспорта), сама служба при этом продолжает работать, просто
    // пользователь не видит прогресс/кнопку «Стоп» в шторке. Лаунчер регистрируется здесь (в
    // теле класса, до onCreate) — так требует registerForActivityResult (до перехода Activity в
    // STARTED). Результат (дали/не дали) не блокирует ничего дальше — это best-effort.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* дали или отказали - в обоих случаях просто продолжаем; сервис работает и без уведомлений */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Должен вызываться до super.onCreate — иначе тема Theme.Runstef.Starting
        // не подхватится и система покажет свой автогенерированный (обрезающий иконку) сплэш.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Только для debug-сборки (android:debuggable) — позволяет открыть chrome://inspect
        // на компьютере и посмотреть консоль/DOM WebView прямо на реальном устройстве, где
        // поведение (в т.ч. кнопка «К текущему дню» в плане) может отличаться от эмулятора.
        // В release-сборке debuggable=false, поэтому здесь ничего не включится.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // ИСПРАВЛЕНО (ревью п.15 "Main-thread blocking work"): PlanRepository(this).listPlans()
        // читает и парсит файлы планов с диска — раньше вызывалось синхронно здесь, в onCreate
        // на главном потоке, ДО setContent, блокируя UI при заметном числе сохранённых планов.
        // Теперь сплэш-экран держится (setKeepOnScreenCondition), пока список планов не
        // загрузится в фоне (Dispatchers.IO), а setContent (и сам первый Compose-кадр)
        // выполняется уже с готовым startDestination — на главном потоке остаётся только
        // isNotEmpty()/выбор маршрута, не файловый I/O.
        var plansLoaded = false
        splashScreen.setKeepOnScreenCondition { !plansLoaded }
        lifecycleScope.launch {
            val hasPlans = withContext(Dispatchers.IO) {
                PlanRepository(this@MainActivity).listPlans().isNotEmpty()
            }
            plansLoaded = true
            setContent {
                RunstefTheme {
                    val authViewModel: AuthViewModel = viewModel()
                    val homeViewModel: HomeViewModel = viewModel()
                    RunstefApp(
                        activity = this@MainActivity,
                        authViewModel = authViewModel,
                        homeViewModel = homeViewModel,
                        startDestination = if (hasPlans) Dest.Plans.route else Dest.Home.route
                    )
                }
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unlocked by authViewModel.unlocked.collectAsState()

    // Защита всего приложения включается, только если есть хотя бы один сохранённый аккаунт
    // Garmin — пересчитываем при каждом возврате приложения на передний план (ON_START), т.к.
    // аккаунт мог появиться/исчезнуть, пока приложение было свёрнуто (а также сразу после
    // добавления первого аккаунта через «＋» на вкладках «Экспорт»/«Аналитика»).
    var hasSavedAccounts by remember {
        mutableStateOf(GarminTokenStore(context).savedAccounts().isNotEmpty())
    }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    hasSavedAccounts = GarminTokenStore(context).savedAccounts().isNotEmpty()
                }
                // ON_STOP больше не блокирует автоматически: раньше authViewModel.lockNow()
                // вызывался на каждый уход в фон (сворачивание, переключение приложений,
                // системный диалог, поворот экрана), из-за чего ПИН/биометрия запрашивались
                // почти на каждое действие. Теперь разблокировка живёт, пока жив процесс
                // приложения (AuthViewModel/unlocked переживают ON_STOP/ON_START), то есть
                // спрашиваем один раз за сессию — при первом запуске/возврате после полного
                // закрытия системой. Ручная блокировка (кнопка "Заблокировать" в настройках,
                // onLockNow) по-прежнему работает.
                else -> {}
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    if (hasSavedAccounts && !unlocked) {
        LockScreen(activity = activity, authViewModel = authViewModel)
        return
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    // На WebView-экранах (калькуляторы, просмотр плана, отчёт аналитики) верхний тулбар скрываем,
    // чтобы оставить только нижнюю навигацию и сэкономить место на экране.
    val hideTopBar = currentRoute?.hierarchy?.any {
        it.route == "tool/{url}" || it.route == "planview?path={path}" || it.route == "analyticsReport?path={path}"
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
                                Dest.Analytics -> Icon(Icons.Filled.Insights, contentDescription = dest.label)
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
                    // Калькуляторы plan/hr_pace/weight/rank открываются с уже подставленными
                    // данными из профиля и аналитики (см. ToolUrlBuilder) — сама сборка URL
                    // читает локальный профиль/SQLite, поэтому уходит в корутину, чтобы не
                    // блокировать UI-поток; для остальных инструментов просто возвращает url как есть.
                    scope.launch {
                        val url = ToolUrlBuilder.build(context, tool)
                        navController.navigate("tool/${Uri.encode(url)}")
                    }
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
                        val planTool = homeViewModel.uiState.value.tools.firstOrNull { it.id == "plan" }
                            ?: com.example.runstef.ui.home.CalculatorTool(
                                id = "plan", title = "", subtitle = "", url = DEFAULT_PLAN_URL,
                                icon = Icons.AutoMirrored.Filled.DirectionsRun
                            )
                        scope.launch {
                            val url = ToolUrlBuilder.build(context, planTool)
                            navController.navigate("tool/${Uri.encode(url)}")
                        }
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
                // ПИН/биометрия теперь защищают всё приложение целиком (см. проверку
                // hasSavedAccounts && !unlocked выше) — здесь отдельного гейта больше не нужно,
                // экран «Экспорт» открывается сразу.
                ExportScreen(preselectedFilePath = plan)
            }
            composable(Dest.Analytics.route) {
                AnalyticsScreen(onOpenReport = { path ->
                    navController.navigate("analyticsReport?path=${Uri.encode(path)}")
                })
            }
            composable(
                route = "analyticsReport?path={path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { entry ->
                val path = Uri.decode(entry.arguments?.getString("path") ?: "")
                AnalyticsReportScreen(filePath = path)
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
    if (update != null && ownVersion != null && effectiveConfig?.updateAvailable == true &&
        !updateDismissed && update.skipKey != skippedVersion
    ) {
        UpdateAvailableDialog(
            latestVersion = update.latestVersion,
            apkUrl = update.apkUrl,
            onDismiss = { homeViewModel.dismissUpdate() },
            onSkip = { homeViewModel.skipVersion(update.skipKey) },
            expectedSha256 = update.sha256
        )
    }
}
