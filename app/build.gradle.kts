import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Crashlytics：只有在 CI 從 Secrets 放入 google-services.json 時才啟用
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// CI 會以 GitHub Actions 的 run number 當作版本號，讓每次建置都能覆蓋安裝更新
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

android {
    namespace = "com.charlie.weather"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.charlie.weather"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
        // 環境部空品 API 金鑰（GitHub Secret MOENV_API_KEY）；沒有時改用 Open-Meteo 的空氣品質
        buildConfigField("String", "MOENV_API_KEY", "\"${System.getenv("MOENV_API_KEY").orEmpty().filterNot { it.isWhitespace() }}\"")
        // Google 金鑰（GitHub Secret GOOGLE_MAPS_API_KEY）：沿路天氣用 Google 地圖顯示路線，行車時間含路況；沒有時用 OpenStreetMap
        val googleMapsKey = System.getenv("GOOGLE_MAPS_API_KEY").orEmpty().filterNot { it.isWhitespace() }
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$googleMapsKey\"")
        manifestPlaceholders["googleMapsApiKey"] = googleMapsKey
        // 交通部 TDX（GitHub Secret TDX_CLIENT_ID、TDX_CLIENT_SECRET）：沿路的道路事件（施工、事故、封閉）；沒有時不顯示
        buildConfigField("String", "TDX_CLIENT_ID", "\"${System.getenv("TDX_CLIENT_ID").orEmpty().filterNot { it.isWhitespace() }}\"")
        buildConfigField("String", "TDX_CLIENT_SECRET", "\"${System.getenv("TDX_CLIENT_SECRET").orEmpty().filterNot { it.isWhitespace() }}\"")
    }

    // 簽章金鑰由 CI 的 GitHub Secrets 提供；未設定時退回 debug 金鑰（可安裝，但無法覆蓋更新）
    val signingStore = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }
    signingConfigs {
        if (signingStore != null) {
            create("release") {
                storeFile = file(signingStore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Roborazzi：執行單元測試時輸出畫面截圖
                it.systemProperty("roborazzi.test.record", "true")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // 路線降雨的地圖（有 Google 金鑰時）
    implementation("com.google.maps.android:maps-compose:6.4.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-crashlytics")

    testImplementation("junit:junit:4.13.2")
    // Android 內建的 org.json 在 JVM 單元測試中只是空殼，改用真正的實作
    testImplementation("org.json:json:20240303")

    // 畫面截圖測試（Robolectric + Roborazzi）
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
