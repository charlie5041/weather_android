package com.charlie.weather.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.charlie.weather.data.City
import com.charlie.weather.data.LocationProvider
import com.charlie.weather.data.Weather
import com.charlie.weather.data.WeatherApi
import com.charlie.weather.data.WeatherRepository
import com.charlie.weather.widget.WeatherWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CityWeatherUi(
    val weather: Weather? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class WeatherViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = WeatherRepository.get(app)
    private val store = repository.store
    private val locationProvider = LocationProvider(app)

    private var locationCity: City? = store.loadLocationCity()
    private var savedCities: List<City> = store.loadCities()

    private val _cities = MutableStateFlow(listOfNotNull(locationCity) + savedCities)
    val cities: StateFlow<List<City>> = _cities.asStateFlow()

    private val _weather = MutableStateFlow<Map<String, CityWeatherUi>>(emptyMap())
    val weather: StateFlow<Map<String, CityWeatherUi>> = _weather.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults = MutableStateFlow<List<City>>(emptyList())
    val searchResults: StateFlow<List<City>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var searchJob: Job? = null
    private var locationJob: Job? = null

    init {
        // 啟動時先用磁碟快取（含氣象署資料）立即顯示，不連網
        _cities.value.forEach { city ->
            viewModelScope.launch {
                val cached = repository.cached(city) ?: return@launch
                updateState(city.id) { state -> if (state.weather == null) state.copy(weather = cached) else state }
            }
        }
    }

    fun hasLocationPermission() = locationProvider.hasPermission()

    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) {
            updateLocation(force = false)
        } else {
            if (locationCity != null) {
                locationCity = null
                store.saveLocationCity(null)
                publishCities()
            }
            ensureDefaultCity()
        }
    }

    /** 開啟 App 或回到前景時呼叫；資料超過 10 分鐘才會重新抓取。 */
    fun refreshAll(force: Boolean = false) {
        if (locationProvider.hasPermission()) updateLocation(force)
        savedCities.forEach { refresh(it, force) }
    }

    fun refresh(city: City, force: Boolean = false) {
        if (city.isCurrentLocation && force && locationProvider.hasPermission()) {
            updateLocation(force = true)
            return
        }
        fetch(city, force)
    }

    private fun fetch(city: City, force: Boolean) {
        val state = _weather.value[city.id]
        if (state?.loading == true) return
        val cached = state?.weather
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAtMillis < STALE_MS) return

        updateState(city.id) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val weather = repository.fetch(city)
                updateState(city.id) { CityWeatherUi(weather = weather) }
                if (repository.primaryCity()?.id == city.id) updateWidget(city, weather)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState(city.id) { it.copy(loading = false, error = "無法更新天氣資料，請檢查網路連線") }
            }
        }
    }

    private fun updateLocation(force: Boolean) {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            locationCity?.let { updateState(it.id) { s -> s.copy(loading = s.weather == null || force) } }
            val location = locationProvider.currentLocation()
            if (location == null) {
                locationCity?.let { updateState(it.id) { s -> s.copy(loading = false) } }
                locationCity?.let { fetch(it, force) } ?: ensureDefaultCity()
                return@launch
            }
            val previous = locationCity
            val movedFar = previous == null ||
                distanceKm(previous.latitude, previous.longitude, location.latitude, location.longitude) > 1.0
            val name = if (movedFar || previous?.name == DEFAULT_LOCATION_NAME) {
                locationProvider.placeName(location.latitude, location.longitude) ?: previous?.name ?: DEFAULT_LOCATION_NAME
            } else {
                previous!!.name
            }
            val city = City(LOCATION_ID, name, DEFAULT_LOCATION_NAME, location.latitude, location.longitude, isCurrentLocation = true)
            locationCity = city
            store.saveLocationCity(city)
            publishCities()
            updateState(city.id) { it.copy(loading = false) }
            fetch(city, force || movedFar)
        }
    }

    private fun ensureDefaultCity() {
        if (_cities.value.isEmpty()) addCity(DEFAULT_CITY)
    }

    fun onQueryChange(value: String) {
        _query.value = value
        searchJob?.cancel()
        if (value.isBlank()) {
            _searchResults.value = emptyList()
            _searching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _searching.value = true
            _searchResults.value = try {
                WeatherApi.searchCities(value.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _searching.value = false
        }
    }

    /** 新增城市並回傳它在頁面中的索引。 */
    fun addCity(city: City): Int {
        clearSearch()
        val existing = _cities.value.indexOfFirst { it.id == city.id }
        if (existing >= 0) return existing
        savedCities = savedCities + city
        store.saveCities(savedCities)
        publishCities()
        if (_weather.value[city.id] == null) updateState(city.id) { it }
        fetch(city, force = false)
        return _cities.value.indexOfFirst { it.id == city.id }
    }

    fun removeCity(city: City) {
        if (city.isCurrentLocation) return
        savedCities = savedCities.filterNot { it.id == city.id }
        store.saveCities(savedCities)
        store.removeCache(city.id)
        _weather.update { it - city.id }
        publishCities()
    }

    /** 依 cities 的索引移動城市；目前位置固定在第一個，不能移動。 */
    fun moveCity(from: Int, to: Int) {
        val offset = if (locationCity != null) 1 else 0
        val f = from - offset
        val t = to - offset
        if (f !in savedCities.indices || t !in savedCities.indices || f == t) return
        savedCities = savedCities.toMutableList().apply { add(t, removeAt(f)) }
        store.saveCities(savedCities)
        publishCities()
    }

    fun clearSearch() {
        searchJob?.cancel()
        _query.value = ""
        _searchResults.value = emptyList()
        _searching.value = false
    }

    private fun publishCities() {
        val previousPrimary = _cities.value.firstOrNull()?.id
        _cities.value = listOfNotNull(locationCity) + savedCities
        val primary = _cities.value.firstOrNull()
        if (primary != null && primary.id != previousPrimary) {
            _weather.value[primary.id]?.weather?.let { updateWidget(primary, it) }
        }
    }

    private fun updateWidget(city: City, weather: Weather) {
        viewModelScope.launch {
            runCatching { WeatherWidget.update(getApplication(), city, weather) }
        }
    }

    private fun updateState(id: String, transform: (CityWeatherUi) -> CityWeatherUi) {
        _weather.update { it + (id to transform(it[id] ?: CityWeatherUi())) }
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    companion object {
        const val LOCATION_ID = "current_location"
        const val DEFAULT_LOCATION_NAME = "我的位置"
        private const val STALE_MS = 10 * 60_000L
        val DEFAULT_CITY = City("geo_1668341", "臺北市", "臺灣", 25.0478, 121.5319)
    }
}
