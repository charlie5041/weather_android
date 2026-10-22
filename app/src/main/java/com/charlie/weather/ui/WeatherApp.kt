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
import com.charlie.weather.data.SharePrivacy
import com.charlie.weather.data.City
import com.charlie.weather.data.Commute
import com.charlie.weather.data.FavoriteRoute
import com.charlie.weather.data.Journey
import com.charlie.weather.data.JourneyStore
import com.charlie.weather.data.RouteData
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.CircularProgressIndicator
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
    val recentPlaces by vm.recentPlaces.collectAsStateWithLifecycle()
    val riding by RideService.active.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.WEATHER) }
    val driving by RideService.driving.collectAsStateWithLifecycle()
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
    val journeyStore = remember { JourneyStore(context) }
    val journeysVersion by JourneyStore.changed.collectAsStateWithLifecycle()
    val savedJourney by JourneyStore.saved.collectAsStateWithLifecycle()
    var journeys by remember { mutableStateOf<List<Journey>>(emptyList()) }
    var openJourneyId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastJourney by remember { mutableStateOf<Journey?>(null) }
    var pickPhotosFor by remember { mutableStateOf<String?>(null) }
    var addingPhotos by remember { mutableStateOf(false) }
    val photoFiles = remember<PhotoFiles>(journeyStore) { { id, photo, thumb -> journeyStore.photoFile(id, photo, thumb) } }
    LaunchedEffect(journeysVersion) { journeys = journeyStore.list() }
    // 上次騎乘中程序被系統結束時，留下的紀錄補存成一趟足跡
    LaunchedEffect(Unit) { journeyStore.recover(active = RideService.active.value || RideService.driving.value) }
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
    fun startRide(data: RouteData) {
        if (!vm.hasLocationPermission()) {
            Toast.makeText(context, "需要定位權限才能在路上提醒前方天氣", Toast.LENGTH_LONG).show()
        } else {
            RideService.start(context, data)
            Toast.makeText(context, "已開始：前方 20 分鐘內會下雨時通知", Toast.LENGTH_SHORT).show()
        }
    }
    val pagerState = rememberPagerState { cities.size }
    val scope = rememberCoroutineScope()
    val commuteMinutes by vm.commuteMinutes.collectAsStateWithLifecycle()
    val commutePlaces = remember(cities) { Commute.homeAndWork(cities) }
    val privacyRadiusM = remember(settingsVersion) { AppSettings(context).sharePrivacyRadiusM }
    val privacyZones = remember(cities, settingsVersion) {
        SharePrivacy.zonesFrom(cities, AppSettings(context).sharePrivacyExcluded, privacyRadiusM)
    }
    // 住家、公司或交通方式改變時重新查行車時間（一天內用記下的值）
    LaunchedEffect(commutePlaces, routeRequest == null) {
        commutePlaces?.let { (home, work) -> vm.loadCommuteMinutes(home, work) }
    }
    val commuteTrip = remember(cities, weather, settingsVersion, commuteMinutes) {
        val settings = AppSettings(context)
        if (!settings.commuteCard) return@remember null
        val (home, work) = commutePlaces ?: return@remember null
        Commute.trip(
            home, work, weather[home.id]?.weather, weather[work.id]?.weather, LocalDateTime.now(),
            settings.commuteMorningHour, settings.commuteEveningHour, commuteMinutes,
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
                val openSettings = { showSettings = true }
                when (tab) {
                    Tab.WEATHER -> HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                        key = { cities.getOrNull(it)?.id ?: it },
                    ) { page ->
                        val city = cities.getOrNull(page) ?: return@HorizontalPager
                        CityWeatherPage(
                            city = city,
                            ui = weather[city.id] ?: CityWeatherUi(loading = true),
                            onRefresh = { vm.refresh(city, force = true) },
                            onOpenDetail = { metric, date -> detail = DetailRequest(city.id, metric, date) },
                            onOpenTyphoon = { typhoonCityId = city.id },
                            page = page,
                            pageCount = cities.size,
                            firstIsLocation = cities.firstOrNull()?.isCurrentLocation == true,
                            onOpenList = { showList = true },
                            onOpenSettings = openSettings,
                        )
                    }
                    Tab.OUTING -> OutingScreen(
                        favorites = favorites,
                        upcoming = upcoming,
                        commute = commuteTrip,
                        rideActive = riding,
                        canReportCameras = vm.canReportCameras,
                        onNewRoute = { routeRequest = RouteRequest(cities.firstOrNull(), null, mode = vm.lastRoute()?.mode) },
                        onOpen = { route, departure -> openFavorite(route, departure) },
                        onOpenCommuteRoute = {
                            commuteTrip?.let { routeRequest = RouteRequest(it.from, it.to, it.departure, vm.lastRoute()?.mode) }
                        },
                        onStartRide = ::startRide,
                        onStopRide = { RideService.stop(context) },
                        onReportCamera = { RideService.report(context) },
                        onOpenSettings = openSettings,
                    )
                    Tab.FOOTPRINTS -> FootprintsScreen(
                        journeys = journeys,
                        onOpen = { openJourneyId = it.id },
                        onOpenSettings = openSettings,
                        photoFiles = photoFiles,
                        rideActive = riding,
                        driving = driving,
                        canReportCameras = vm.canReportCameras,
                        recordJourneys = remember(settingsVersion) { AppSettings(context).recordJourneys },
                        onStartDrive = {
                            if (!vm.hasLocationPermission()) {
                                Toast.makeText(context, "需要定位權限才能提醒前方的測速照相", Toast.LENGTH_LONG).show()
                            } else {
                                RideService.startDrive(context)
                            }
                        },
                        onStop = { RideService.stop(context) },
                        onReportCamera = { RideService.report(context) },
                    )
                }
                TabBar(
                    selected = tab,
                    onSelect = { tab = it },
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
                    places = cities,
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
                            recentPlaces = recentPlaces,
                            onRememberPlace = vm::rememberPlace,
                            onClearRecentPlaces = vm::clearRecentPlaces,
                            onSearch = vm::searchAddress,
                            onUseCurrentLocation = vm::currentAddress,
                            onLoad = vm::routeData,
                            trafficAware = vm.trafficAwareRoutes,
                            favorites = favorites,
                            onSaveFavorite = vm::saveFavorite,
                            onRemoveFavorite = vm::removeFavorite,
                            rideActive = riding,
                            onStartRide = ::startRide,
                            onStopRide = { RideService.stop(context) },
                            onReportCamera = if (vm.canReportCameras) {
                                { RideService.report(context) }
                            } else {
                                null
                            },
                            onClose = { routeRequest = null },
                        )
                    }
                }
            }
            val openJourney = openJourneyId?.let { id -> journeys.firstOrNull { it.id == id } }
            LaunchedEffect(openJourney) { openJourney?.let { lastJourney = it } }
            AnimatedVisibility(
                visible = openJourney != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                (openJourney ?: lastJourney)?.let { j ->
                    FootprintDetailScreen(
                        journey = j,
                        onRename = { name -> scope.launch { journeyStore.rename(j.id, name) } },
                        onDelete = {
                            openJourneyId = null
                            scope.launch { journeyStore.delete(j.id) }
                        },
                        onClose = { openJourneyId = null },
                        onAddPhotos = { uris ->
                            scope.launch {
                                addingPhotos = true
                                val added = try {
                                    journeyStore.addPhotos(j.id, uris)
                                } finally {
                                    addingPhotos = false
                                }
                                Toast.makeText(context, photosAddedMessage(uris.size, added.size, added.count { it.position == null }), Toast.LENGTH_LONG).show()
                            }
                        },
                        onMovePhoto = { photo, fraction -> scope.launch { journeyStore.movePhoto(j.id, photo.id, fraction) } },
                        onDeletePhoto = { photo -> scope.launch { journeyStore.deletePhoto(j.id, photo.id) } },
                        photoFiles = photoFiles,
                        addingPhotos = addingPhotos,
                        pickOnOpen = pickPhotosFor == j.id,
                        onPickHandled = { pickPhotosFor = null },
                        privacyZones = privacyZones,
                        privacyRadiusM = privacyRadiusM,
                    )
                }
            }
            savedJourney?.let { j ->
                JourneySavedDialog(
                    journey = j,
                    onAddPhotos = {
                        JourneyStore.clearSaved()
                        pickPhotosFor = j.id
                        showList = false
                        showSettings = false
                        detail = null
                        routeRequest = null
                        tab = Tab.FOOTPRINTS
                        openJourneyId = j.id
                    },
                    onDismiss = { JourneyStore.clearSaved() },
                )
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
        BackHandler(enabled = openJourneyId != null) { openJourneyId = null }
    }
}

private enum class Tab(val label: String) { WEATHER("天氣"), OUTING("出門"), FOOTPRINTS("足跡") }

/** 底部分頁：天氣、出門、足跡。 */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF0A192D).copy(alpha = 0.78f))
            .navigationBarsPadding()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val color = if (t == selected) Color.White else Color.White.copy(alpha = 0.55f)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = t == selected, role = Role.Tab, onClick = { onSelect(t) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (t) {
                    Tab.WEATHER -> WeatherTabIcon(color, Modifier.size(22.dp))
                    Tab.OUTING -> RouteIcon(color, Modifier.size(22.dp))
                    Tab.FOOTPRINTS -> FootprintsIcon(color, Modifier.size(22.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(t.label, color = color, fontSize = 12.sp, fontWeight = if (t == selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** 天氣分頁：太陽與雲。 */
@Composable
private fun WeatherTabIcon(color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(color, radius = w * 0.16f, center = androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.36f), style = stroke)
        val cloud = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.36f, h * 0.84f)
            lineTo(w * 0.74f, h * 0.84f)
            cubicTo(w * 0.98f, h * 0.84f, w * 0.98f, h * 0.52f, w * 0.74f, h * 0.54f)
            cubicTo(w * 0.68f, h * 0.36f, w * 0.42f, h * 0.38f, w * 0.38f, h * 0.58f)
            cubicTo(w * 0.2f, h * 0.58f, w * 0.2f, h * 0.84f, w * 0.36f, h * 0.84f)
            close()
        }
        drawPath(cloud, color, style = stroke)
    }
}

/** 出門分頁：起點、彎曲路線與終點。 */
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

/** 足跡分頁：地圖圖釘。 */
@Composable
private fun FootprintsIcon(color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val pin = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.92f)
            cubicTo(w * 0.28f, h * 0.66f, w * 0.18f, h * 0.52f, w * 0.18f, h * 0.38f)
            cubicTo(w * 0.18f, h * 0.2f, w * 0.32f, h * 0.06f, w * 0.5f, h * 0.06f)
            cubicTo(w * 0.68f, h * 0.06f, w * 0.82f, h * 0.2f, w * 0.82f, h * 0.38f)
            cubicTo(w * 0.82f, h * 0.52f, w * 0.72f, h * 0.66f, w * 0.5f, h * 0.92f)
            close()
        }
        drawPath(pin, color, style = stroke)
        drawCircle(color, radius = w * 0.1f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.38f))
    }
}

/** 例：已加入 5 張照片，其中 1 張沒有拍攝時間或不在這趟騎乘期間，可以點照片手動放到路線上 */
internal fun photosAddedMessage(picked: Int, added: Int, unplaced: Int): String = buildString {
    append(if (added == 0) "照片無法讀取" else "已加入 $added 張照片")
    if (unplaced > 0) append("，其中 $unplaced 張沒有拍攝時間或不在這趟騎乘期間，可以點照片手動放到路線上")
    if (added in 1 until picked) append("；${picked - added} 張無法讀取")
}
