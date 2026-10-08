package com.charlie.weather.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.charlie.weather.data.AppSettings
import com.charlie.weather.data.City
import com.charlie.weather.data.Commute
import com.charlie.weather.data.FavoriteRoute
import com.charlie.weather.sync.RideService
import com.charlie.weather.sync.WeatherNotifier
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@Composable
fun WeatherApp(
    vm: WeatherViewModel = viewModel(),
    /** 從其他 App 分享進來的文字（Google 地圖的路線連結） */
    sharedText: String? = null,
    onSharedTextHandled: () -> Unit = {},
    /** 點常用路線的出發提醒通知開啟時，要開的路線 id */
    favoriteRouteId: String? = null,
    onFavoriteRouteHandled: () -> Unit = {},
) {
    val cities by vm.cities.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val riding by RideService.active.collectAsStateWithLifecycle()

    var showList by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<DetailRequest?>(null) }
    var typhoonCityId by remember { mutableStateOf<String?>(null) }
    var showPlaceEditor by remember { mutableStateOf(false) }
    var editingPlace by remember { mutableStateOf<City?>(null) }
    var settingsVersion by remember { mutableIntStateOf(0) }
    var routeRequest by remember { mutableStateOf<RouteRequest?>(null) }
    var lastRouteRequest by remember { mutableStateOf<RouteRequest?>(null) }
    LaunchedEffect(routeRequest) { routeRequest?.let { lastRouteRequest = it } }
    val context = LocalContext.current
    LaunchedEffect(sharedText) {
        val text = sharedText ?: return@LaunchedEffect
        Toast.makeText(context, "正在讀取 Google 地圖路線…", Toast.LENGTH_SHORT).show()
        // 讀完才清掉：清掉會改變 key，讓這個 effect 被取消
        val request = try {
            vm.routeFromLink(text)
        } finally {
            onSharedTextHandled()
        }
        if (request == null) {
            Toast.makeText(context, "無法讀取路線，請在 Google 地圖規劃路線後再分享", Toast.LENGTH_LONG).show()
        } else {
            showList = false
            showSettings = false
            detail = null
            routeRequest = request
        }
    }
    /** 帶入上次查詢的路線；第一次使用時從目前看的頁面出發，再選終點 */
    fun defaultRouteRequest(current: City?): RouteRequest =
        vm.lastRoute()?.let { RouteRequest(it.from, it.to, mode = it.mode, via = it.via) } ?: RouteRequest(current, null)

    fun openFavorite(route: FavoriteRoute, departure: LocalDateTime?) {
        val r = vm.latestRoute(route)
        showList = false
        showSettings = false
        detail = null
        routeRequest = RouteRequest(r.from, r.to, departure, r.mode, r.via)
    }
    LaunchedEffect(favoriteRouteId) {
        val id = favoriteRouteId ?: return@LaunchedEffect
        vm.favorites.value.firstOrNull { it.id == id }?.let { openFavorite(it, it.reminder?.next(LocalDateTime.now())) }
        onFavoriteRouteHandled()
    }
    val pagerState = rememberPagerState { cities.size }
    val scope = rememberCoroutineScope()
    val commuteTrip = remember(cities, weather, settingsVersion) {
        val settings = AppSettings(context)
        if (!settings.commuteCard) return@remember null
        val (home, work) = Commute.homeAndWork(cities) ?: return@remember null
        Commute.trip(
            home, work, weather[home.id]?.weather, weather[work.id]?.weather, LocalDateTime.now(),
            settings.commuteMorningHour, settings.commuteEveningHour,
        )
    }

    // 通知權限（Android 13+）只在第一次啟動時詢問一次，之後可在設定頁開啟
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotificationPermissionOnce() {
        val settings = AppSettings(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.askedNotificationPermission &&
            !WeatherNotifier.canNotify(context)
        ) {
            settings.askedNotificationPermission = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        vm.onLocationPermissionResult(granted.values.any { it })
        askNotificationPermissionOnce()
    }

    LaunchedEffect(Unit) {
        if (!vm.hasLocationPermission()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            askNotificationPermissionOnce()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAll() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (cities.isEmpty()) {
                WeatherBackground(1, true)
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    key = { cities.getOrNull(it)?.id ?: it },
                ) { page ->
                    val city = cities.getOrNull(page) ?: return@HorizontalPager
                    val outingCard: @Composable () -> Unit = {
                        TripCard(
                            favorites = favorites,
                            upcoming = upcoming,
                            modifier = Modifier.fillMaxWidth(),
                            onOpen = { route, departure -> openFavorite(route, departure) },
                            onNewRoute = { routeRequest = defaultRouteRequest(city) },
                        )
                    }
                    CityWeatherPage(
                        city = city,
                        ui = weather[city.id] ?: CityWeatherUi(loading = true),
                        onRefresh = { vm.refresh(city, force = true) },
                        onOpenDetail = { metric, date -> detail = DetailRequest(city.id, metric, date) },
                        onOpenTyphoon = { typhoonCityId = city.id },
                        // 通勤卡片顯示在第一頁與住家、公司頁
                        commute = commuteTrip?.takeIf { page == 0 || city.id == it.from.id || city.id == it.to.id },
                        onOpenCommuteRoute = {
                            commuteTrip?.let { routeRequest = RouteRequest(it.from, it.to, it.departure, vm.lastRoute()?.mode) }
                        },
                        outing = outingCard.takeIf { page == 0 },
                    )
                }
                BottomBar(
                    pageCount = cities.size,
                    currentPage = pagerState.currentPage,
                    firstIsLocation = cities.firstOrNull()?.isCurrentLocation == true,
                    onList = { showList = true },
                    onRoute = { routeRequest = defaultRouteRequest(cities.getOrNull(pagerState.currentPage)) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            AnimatedVisibility(
                visible = showList,
                enter = fadeIn() + scaleIn(initialScale = 1.08f),
                exit = fadeOut() + scaleOut(targetScale = 1.08f),
            ) {
                CityListScreen(
                    cities = cities,
                    weather = weather,
                    query = query,
                    results = results,
                    searching = searching,
                    onQueryChange = vm::onQueryChange,
                    onAdd = { city ->
                        val index = vm.addCity(city)
                        showList = false
                        scope.launch { pagerState.scrollToPage(index) }
                    },
                    onSelect = { index ->
                        showList = false
                        scope.launch { pagerState.scrollToPage(index) }
                    },
                    onRemove = vm::removeCity,
                    onMove = vm::moveCity,
                    onOpenSettings = { showSettings = true },
                    onAddPlace = {
                        editingPlace = null
                        showPlaceEditor = true
                    },
                    onEditPlace = { city ->
                        editingPlace = city
                        showPlaceEditor = true
                    },
                    onClose = {
                        vm.clearSearch()
                        showList = false
                    },
                )
            }
            AnimatedVisibility(
                visible = showSettings,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
            ) {
                SettingsScreen(
                    primaryCityName = cities.firstOrNull()?.name,
                    onDataSourceChanged = { vm.refreshAll(force = true) },
                    onClose = {
                        showSettings = false
                        settingsVersion++
                    },
                )
            }
            val detailRequest = detail
            val detailCity = detailRequest?.let { req -> cities.firstOrNull { it.id == req.cityId } }
            val detailWeather = detailCity?.let { weather[it.id]?.weather }
            AnimatedVisibility(
                visible = detailRequest != null && detailWeather != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                if (detailRequest != null && detailCity != null && detailWeather != null) {
                    DetailScreen(
                        city = detailCity,
                        weather = detailWeather,
                        initialMetric = detailRequest.metric,
                        initialDate = detailRequest.date,
                        onClose = { detail = null },
                    )
                }
            }
            val typhoonCity = typhoonCityId?.let { id -> cities.firstOrNull { it.id == id } }
            val typhoons = typhoonCity?.let { weather[it.id]?.weather?.typhoons }.orEmpty()
            AnimatedVisibility(
                visible = typhoonCity != null && typhoons.isNotEmpty(),
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                if (typhoonCity != null && typhoons.isNotEmpty()) {
                    TyphoonScreen(typhoons, typhoonCity, onClose = { typhoonCityId = null })
                }
            }
            AnimatedVisibility(
                visible = routeRequest != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                // 關閉動畫期間 routeRequest 已是 null，沿用最後一次的請求讓畫面不閃掉
                (routeRequest ?: lastRouteRequest)?.let { req ->
                    androidx.compose.runtime.key(req) {
                        RouteScreen(
                            request = req,
                            places = cities,
                            onSearch = vm::searchAddress,
                            onUseCurrentLocation = vm::currentAddress,
                            onLoad = vm::routeData,
                            trafficAware = vm.trafficAwareRoutes,
                            favorites = favorites,
                            onSaveFavorite = vm::saveFavorite,
                            onRemoveFavorite = vm::removeFavorite,
                            rideActive = riding,
                            onStartRide = { data ->
                                if (!vm.hasLocationPermission()) {
                                    Toast.makeText(context, "需要定位權限才能在路上提醒前方天氣", Toast.LENGTH_LONG).show()
                                } else {
                                    RideService.start(context, data)
                                    Toast.makeText(context, "已開始：前方 20 分鐘內會下雨時通知", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onStopRide = { RideService.stop(context) },
                            onClose = { routeRequest = null },
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = showPlaceEditor,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                PlaceEditorScreen(
                    existing = editingPlace,
                    onSearch = vm::searchAddress,
                    onUseCurrentLocation = vm::currentAddress,
                    onSave = { label, result ->
                        val index = vm.saveAddressPlace(editingPlace?.id, label, result)
                        showPlaceEditor = false
                        showList = false
                        scope.launch { pagerState.scrollToPage(index) }
                    },
                    onClose = { showPlaceEditor = false },
                )
            }
        }
        BackHandler(enabled = showList) {
            vm.clearSearch()
            showList = false
        }
        BackHandler(enabled = detail != null) { detail = null }
        BackHandler(enabled = typhoonCityId != null) { typhoonCityId = null }
        BackHandler(enabled = showSettings) { showSettings = false }
        BackHandler(enabled = showPlaceEditor) { showPlaceEditor = false }
        BackHandler(enabled = routeRequest != null) { routeRequest = null }
    }
}

@Composable
private fun BottomBar(
    pageCount: Int,
    currentPage: Int,
    firstIsLocation: Boolean,
    onList: () -> Unit,
    onRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.22f))
            .navigationBarsPadding()
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.18f))
                .clickable(role = Role.Button, onClick = onRoute)
                .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteIcon(Color.White, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("沿路天氣", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(pageCount) { i ->
                val color = if (i == currentPage) Color.White else Color.White.copy(alpha = 0.4f)
                if (i == 0 && firstIsLocation) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
                } else {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                }
            }
        }
        IconButton(onClick = onList) {
            Icon(Icons.Filled.Menu, contentDescription = "城市列表", tint = Color.White)
        }
    }
}

/** 沿路天氣按鈕：起點、彎曲路線與終點。 */
@Composable
private fun RouteIcon(color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.2f, h * 0.82f)
            cubicTo(w * 0.2f, h * 0.45f, w * 0.8f, h * 0.6f, w * 0.8f, h * 0.22f)
        }
        drawPath(
            path,
            color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(stroke * 1.6f, stroke * 1.4f)),
            ),
        )
        drawCircle(color, radius = w * 0.12f, center = androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.82f))
        drawCircle(color, radius = w * 0.14f, center = androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.2f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
    }
}
