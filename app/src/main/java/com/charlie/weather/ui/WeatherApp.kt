package com.charlie.weather.ui

import android.Manifest
import android.os.Build
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.charlie.weather.data.AppSettings
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

@Composable
fun WeatherApp(vm: WeatherViewModel = viewModel()) {
    val cities by vm.cities.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()

    var showList by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<DetailRequest?>(null) }
    var typhoonCityId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pagerState = rememberPagerState { cities.size }
    val scope = rememberCoroutineScope()

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
                    CityWeatherPage(
                        city = city,
                        ui = weather[city.id] ?: CityWeatherUi(loading = true),
                        onRefresh = { vm.refresh(city, force = true) },
                        onOpenDetail = { metric, date -> detail = DetailRequest(city.id, metric, date) },
                        onOpenTyphoon = { typhoonCityId = city.id },
                    )
                }
                BottomBar(
                    pageCount = cities.size,
                    currentPage = pagerState.currentPage,
                    firstIsLocation = cities.firstOrNull()?.isCurrentLocation == true,
                    onList = { showList = true },
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
                    onClose = { showSettings = false },
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
        }
        BackHandler(enabled = showList) {
            vm.clearSearch()
            showList = false
        }
        BackHandler(enabled = detail != null) { detail = null }
        BackHandler(enabled = typhoonCityId != null) { typhoonCityId = null }
        BackHandler(enabled = showSettings) { showSettings = false }
    }
}

@Composable
private fun BottomBar(
    pageCount: Int,
    currentPage: Int,
    firstIsLocation: Boolean,
    onList: () -> Unit,
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
        Spacer(Modifier.size(48.dp))
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
