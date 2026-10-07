# 出行看天氣（iPhone 風格 Android 天氣 App）

以 Kotlin + Jetpack Compose 打造、仿 iOS 天氣介面的 Android App，台灣地區整合中央氣象署與環境部資料。

## 功能

- 背景漸層隨天氣與日夜變化，有雲、雨、雪、霧、閃電、星空動畫
- 目前溫度、24 小時逐時與 10 日預報；點卡片可看整天的逐時圖表（溫度、體感、降雨、風、紫外線、濕度）
- 資訊卡：空氣品質、紫外線、日出日落、風（指南針）、降雨量、體感、濕度、能見度、氣壓、雲量
- 天氣特報卡片、颱風路徑卡片與可縮放的颱風路徑圖
- 目前位置與多城市；離線模糊搜尋（台／臺不分、英文名、縮寫與機場代碼）；iOS 風格編輯模式
- 常用地點：以地址或目前位置新增住家、公司、學校等地點，天氣使用離該地址最近的氣象署測站與雨量站
- 通勤預報：有住家與公司（或學校）時，主畫面顯示下一趟平日通勤兩地的天氣，並可在出發前 1.5 小時推送帶傘提醒
- 路線降雨：輸入起點與終點（或從通勤卡片開啟），沿路線每約 3 公里依預估經過時間查降雨機率，並建議較不會淋雨的出發時間；路線畫在地圖上，可開啟 Google 地圖導航，並記住上次查詢的路線。沒有 Google 金鑰時，行車時間依市區常見速度（機車 25、汽車 22、腳踏車 13、步行 4.5 km/h）估算並標示為預估
- 桌面小工具（小／中／大）；主要城市與常用地點的降雨提醒、天氣特報，以及每日早晨通知
- 設定：°C/°F、風速單位、是否使用中央氣象署資料、通勤卡片與通勤通知

## 資料來源與授權

| 資料 | 來源 | 授權 |
| --- | --- | --- |
| 台灣測站觀測、鄉鎮預報、天氣特報、颱風路徑 | [中央氣象署開放資料](https://opendata.cwa.gov.tw/) | [政府資料開放授權條款－第 1 版](https://data.gov.tw/license) |
| 台灣測站空氣品質（AQI） | [環境部環境資料開放平臺](https://data.moenv.gov.tw/) | [政府資料開放授權條款－第 1 版](https://data.gov.tw/license) |
| 全球天氣預報、空氣品質 | [Open-Meteo](https://open-meteo.com/) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| 線上城市搜尋 | [Open-Meteo Geocoding API](https://open-meteo.com/en/docs/geocoding-api)（GeoNames 資料） | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| 地址查詢（常用地點） | Android 系統 Geocoder（通常由 Google Play 服務提供） | 依裝置定位服務的條款 |
| 世界城市名稱與座標（`world_cities.json`） | [GeoNames](https://www.geonames.org/) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| 路線規劃（有金鑰時） | [Google Routes API](https://developers.google.com/maps/documentation/routes)，含路況 | [Google 地圖平台服務條款](https://cloud.google.com/maps-platform/terms) |
| 路線規劃（沒有金鑰時） | [FOSSGIS 路線服務](https://routing.openstreetmap.de/)（OpenStreetMap 資料） | [ODbL](https://www.openstreetmap.org/copyright) |
| 路線地圖（有金鑰時） | Google Maps SDK for Android | [Google 地圖平台服務條款](https://cloud.google.com/maps-platform/terms) |
| 路線地圖（沒有金鑰時） | [OpenStreetMap 圖磚](https://operations.osmfoundation.org/policies/tiles/) | [ODbL](https://www.openstreetmap.org/copyright) |
| 東亞陸地輪廓（`east_asia_land.json`） | [Natural Earth](https://www.naturalearthdata.com/) | 公有領域 |

內建資料的產生腳本放在 `tools/`。

## CI/CD

`.github/workflows/android.yml` 在每次 push 時（只改 `.md` 文件時不跑）：

1. 執行單元測試與畫面截圖測試，截圖上傳為 Actions artifact（保留 7 天），方便檢查排版
2. 只有 `main` 與手動執行時才建置已簽章的 release APK，並發佈到 **Firebase App Distribution**，測試者用 App Tester 安裝更新

> 這是公開 repo，APK 內含 Firebase 設定與環境部 API 金鑰，因此**不發佈到 GitHub Release**，只透過 App Tester 給指定的測試者。

### 需要的 GitHub Secrets

| Secret | 用途 |
| --- | --- |
| `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` | APK 簽章（同一把金鑰才能覆蓋更新） |
| `FIREBASE_APP_ID`、`FIREBASE_SERVICE_ACCOUNT`、`FIREBASE_TESTERS` | 發佈到 App Tester |
| `GOOGLE_SERVICES_JSON` | Crashlytics（選用） |
| `MOENV_API_KEY` | 環境部 AQI（選用；沒有時使用 Open-Meteo） |
| `GOOGLE_MAPS_API_KEY` | Google 地圖（選用）：路線降雨用 Google 地圖顯示，路線與行車時間含路況；沒有時使用 OpenStreetMap、未含路況 |

`GOOGLE_MAPS_API_KEY` 的設定方式：在 Google Cloud 建立專案並啟用 **Maps SDK for Android**（顯示地圖，行動版免費）與 **Routes API**（路況與行車時間；需綁定帳單，個人使用通常在每月免費額度內），建立 API 金鑰後
1. 「應用程式限制」選 **Android 應用程式**，加入套件名稱 `com.charlie.weather` 與 release 簽章金鑰的 SHA-1（`keytool -list -v -keystore release.jks`）
2. 「API 限制」只勾選 **Maps SDK for Android** 與 **Routes API**

金鑰會包含在 APK 內，上述限制可以避免被其他 App 盜用。

Fork 這個專案自行建置時，沒有這些 Secrets 也能編譯，只是會用臨時的 debug 金鑰簽章，也不會發佈。

## 本機建置

```bash
./gradlew testDebugUnitTest assembleDebug   # 需要 JDK 17 與 Android SDK
```

## 授權

程式碼以 [MIT License](LICENSE) 授權。`app/src/main/assets/` 與測試資料中的內建資料，依上方「資料來源與授權」表格中各來源的授權條款使用（例如 GeoNames 需標示出處）。
