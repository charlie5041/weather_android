package com.charlie.weather.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedRouteTest {
    @Test
    fun remembersLastRoute() {
        val store = CityStore(ApplicationProvider.getApplicationContext())
        assertNull(store.loadLastRoute())
        val home = City("addr_h", "內湖區", "臺北市內湖區瑞光路", 25.08, 121.57, label = "住家", address = "臺北市內湖區瑞光路")
        val office = City("route_25.03000_121.56000", "信義區", "臺北市信義區市府路", 25.03, 121.56, address = "臺北市信義區市府路")
        store.saveLastRoute(SavedRoute(home, office, TravelMode.BIKE))
        assertEquals(SavedRoute(home, office, TravelMode.BIKE), store.loadLastRoute())
    }
}
