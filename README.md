# 一路晴（iPhone 風格 Android 天氣 App）

以 Kotlin + Jetpack Compose 打造、仿 iOS 天氣介面的 Android App，台灣地區整合中央氣象署與環境部資料。

## 功能

- 背景漸層隨天氣與日夜變化，有雲、雨、雪、霧、閃電、星空動畫
- 目前溫度、24 小時逐時與 10 日預報；點卡片可看整天的逐時圖表（溫度、體感、降雨、風、紫外線、濕度）
- 資訊卡：空氣品質、紫外線、日出日落、風（指南針）、降雨量、體感、濕度、能見度、氣壓、雲量
- 天氣特報卡片、颱風卡片（多個颱風合併在同一張路徑圖）與可縮放的颱風路徑圖
- 目前位置與多城市；離線模糊搜尋（台／臺不分、英文名、縮寫與機場代碼）；iOS 風格編輯模式
- 常用地點：以地址或目前位置新增住家、公司、學校等地點，天氣使用離該地址最近的氣象署測站與雨量站
- 通勤預報：有住家與公司（或學校）時，主畫面顯示下一趟平日通勤的出發與抵達時間（依路線行車時間估計）及兩地當時的天氣，並可在出發前 1.5 小時推送帶傘提醒
- 沿路天氣：輸入起點與終點（或從通勤卡片、底部「沿路天氣」按鈕開啟），沿路線每隔一段距離（可在設定選 1–10 公里，預設 3 公里）依預估經過時間查天氣
  - 最上方是一句大字結論（例如「08:20 起可能下雨」），下面是雨況時間軸：整條是行程時間，顏色與高度表示經過時的雨勢
  - 沿途提醒：經過時生效中的天氣特報、強陣風、行程中日落、冷（機車、單車含車速風寒）、熱與紫外線
  - 出發時間比較條：現在與之後每 30 分鐘出發時沿途最高的降雨機率，明顯比較不會淋雨的時間標「建議」；可切換為「抵達時間」，依行車時間往回推出發時間；自訂時間可選 7 天內的日期
  - 替代路線：沒有途經點時最多比較三條路線的行車時間與沿途最高降雨機率，標出最快與較不會淋雨的一條
  - 雷達短時預報：1 小時內出發時，1 小時內會經過的點改用中央氣象署「未來1小時雷達定量降雨預報」判斷會不會下雨，並把雨區畫在路線地圖上
  - 道路事件（有 TDX 金鑰時）：3 小時內出發時列出路線 0.5 公里內的國道、省道即時事件（施工、事故、封閉…）
  - 騎乘中模式：按「開始騎乘」後以前景服務持續定位，常駐通知顯示前方天氣與抵達時間，前方 20 分鐘內會下雨時跳出提醒；抵達終點自動結束
  - 測速照相（設定開啟後）：沿途提醒列出路線會經過的測速照相，地圖標出位置；騎乘中接近時提醒，詳見下方「[測速照相提醒](#測速照相提醒)」
  - 沿途清單把連續經過同一鄉鎮的點合併成一行（可展開）；路線畫在地圖上，可開啟 Google 地圖導航，並記住上次查詢的路線
  - 也可以在 Google 地圖規劃好路線（含途經點）後，用「分享」傳給本 App 查沿途天氣。沒有 Google 金鑰時，行車時間依市區常見速度（機車 25、汽車 22、腳踏車 13、步行 4.5 km/h）估算並標示為預估
- 測速照相提醒：固定式（警政署資料）與使用者回報的移動式測速，沿路天氣、騎乘中與不設目的地的行車提醒都會提醒，可語音播報，詳見下方
- 常用路線：在沿路天氣按「☆ 常用」存下路線，可設定每週哪幾天、幾點出發；主畫面第一頁的「出門」卡片顯示 12 小時內下一趟的沿路結論與雨況時間軸，出發前 1.5 小時內推送沿路天氣通知
- Android Auto（天氣類 App）：車機上顯示目前天氣與常用路線現在出發的沿路結論
- 桌面小工具（小／中／大）；主要城市與常用地點的降雨提醒、天氣特報，以及每日早晨通知
- 設定：°C/°F、風速單位、是否使用中央氣象署資料、通勤卡片與通勤通知、沿路天氣的取樣間距、測速照相提醒、語音播報

## 測速照相提醒

在「設定 → 沿路天氣」打開「**測速照相提醒**」（預設關閉）。只適用機車與汽車，步行與單車不提醒。

### 在哪裡提醒

| 地方 | 內容 |
| --- | --- |
| 沿路天氣 | 沿途提醒最後一行顯示「📷 沿途 N 處測速照相，速限 50–70」；地圖上固定式是深色方塊（Google 地圖為橘色標記），移動式是橘色方塊（紅色標記）。沒有照相時顯示「沿途沒有固定式測速照相」，資料下載失敗時顯示「測速照相資料暫時無法下載」，所以一眼就知道功能有在運作 |
| 騎乘中模式 | 沿路天氣按「開始騎乘／開始出發」。路線前方 500 公尺內有測速照相時通知（例如「📷 前方 300 公尺測速照相・速限 50」），超過速限時多一句「請減速」；偏離路線 100 公尺以上時不提醒（可能在別條路上） |
| 行車提醒（不設目的地） | 從「設定 → 行車提醒」或通知欄快速設定的「**測速提醒**」方塊開始。依 GPS 行進方向提醒前方 500 公尺內、拍攝方向相符的測速照相；停留超過 20 分鐘自動結束 |
| 語音播報 | 「設定 → 語音播報」（預設開啟）：騎乘中與行車提醒念出測速照相與前方降雨，使用導航語音的音訊類型 |

同一處測速照相 10 分鐘內只提醒一次。

### 怎麼判斷

- **在不在路上**：照相離路線 60 公尺內才算，避免算到旁邊的平行道路
- **拍攝方向**：資料的「南向北」「北往南」「往南」「南下車道」「西南向東北」等換成行進方位，與路線方向相差 75 度以上就不算（排除對向車道）；「雙向」「南北雙向」「區間測速」或看不懂的寫法（例如「往大溪方向」）任何方向都算
- **橋上、橋下**：沿路天氣與騎乘中模式保留路線每一段的路名（OSRM 的路名，或 Google 的導航指示）。照相地址寫「高架」「快速道路」「國道」「陸橋」，而路線那一段是平面道路（或反過來）時不提醒，例如走市民大道時不會提醒上方市民大道高架道路的照相。匝道、出口、沒寫清楚的「台XX線」、使用者回報的移動式測速看不出在哪一層，一律保留
- **道路類型標示**：照相在高架、國道、快速道路或陸橋上時，通知標題與語音會標出來（例如「📷 前方 300 公尺測速照相（高架）・速限 70」「……在高架上……」），行車提醒沒有路線可比對時也能自己判斷
- **時速**：取「GPS 回報的速度」與「最近 6 秒實際移動距離換算的速度」較低者，避免速度異常時誤提醒「請減速」；有 GPS 時不使用網路定位
- **定位頻率**：有測速照相要提醒時改為每 2 秒、10 公尺定位一次（較耗電），否則維持 20 秒、100 公尺

### 移動式測速回報

- 騎乘中或行車提醒時，按通知的「**回報測速**」，或 App 內的「**📷 回報移動式測速**」按鈕（沿路天氣騎乘中、設定的行車提醒進行中）
- 送出目前位置與行進方向，**2 小時內**提醒經過、同方向的其他使用者，之後自動失效
- 附近 150 公尺內已有同方向回報時不重複新增；同一支手機 2 分鐘內只能回報一次
- 回報失敗時直接顯示原因（例如「Firebase 尚未啟用匿名登入」「Firestore 規則不允許」），同時記錄到 Crashlytics
- 需要 Firebase 設定，見下方「[移動式測速回報的 Firebase 設定](#移動式測速回報的-firebase-設定)」；沒有設定時不顯示回報按鈕，只提醒固定式測速照相

### 資料與限制

- 固定式測速照相來自警政署「[測速執法設置點](https://data.gov.tw/dataset/7320)」CSV（全國約 1,900 處、240KB），第一次使用時下載，存在手機裡每 7 天更新；下載失敗時沿用舊資料
- 官方資料不定期更新，座標與現場可能有誤差；移動式測速只有使用者回報，不保證完整。**請依實際速限行駛**
- 只涵蓋台灣

### 用模擬定位測試

不用上路也能測試提醒：安裝可模擬路線與時速的 App（例如 Lockito），在「開發人員選項 → 選取模擬位置應用程式」選它，畫一條經過測速照相的路線，再在一路晴開始騎乘或行車提醒。測完記得關掉模擬位置。

## 資料來源與授權

| 資料 | 來源 | 授權 |
| --- | --- | --- |
| 台灣測站觀測、鄉鎮預報、天氣特報、颱風路徑、未來 1 小時雷達定量降雨預報 | [中央氣象署開放資料](https://opendata.cwa.gov.tw/) | [政府資料開放授權條款－第 1 版](https://data.gov.tw/license) |
| 國道、省道即時道路事件（有金鑰時） | [交通部 TDX 運輸資料流通服務](https://tdx.transportdata.tw/) | [政府資料開放授權條款－第 1 版](https://data.gov.tw/license) |
| 固定式測速照相地點（設定開啟時，每週更新） | [警政署「測速執法設置點」](https://data.gov.tw/dataset/7320) | [政府資料開放授權條款－第 1 版](https://data.gov.tw/license) |
| 移動式測速 | 使用者回報（Firebase Firestore） | — |
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
| `GOOGLE_SERVICES_JSON` | Crashlytics 與移動式測速回報（選用） |
| `MOENV_API_KEY` | 環境部 AQI（選用；沒有時使用 Open-Meteo） |
| `TDX_CLIENT_ID`、`TDX_CLIENT_SECRET` | 交通部 TDX（選用）：沿路天氣顯示路線附近的即時道路事件；在 [TDX 會員中心](https://tdx.transportdata.tw/) 申請 API 金鑰取得 |
| `GOOGLE_MAPS_API_KEY` | Google 地圖（選用）：沿路天氣用 Google 地圖顯示，路線與行車時間含路況；沒有時使用 OpenStreetMap、未含路況 |

`GOOGLE_MAPS_API_KEY` 的設定方式：在 Google Cloud 建立專案並啟用 **Maps SDK for Android**（顯示地圖，行動版免費）與 **Routes API**（路況與行車時間；需綁定帳單，個人使用通常在每月免費額度內），建立 API 金鑰後
1. 「應用程式限制」選 **Android 應用程式**，加入套件名稱 `com.charlie.weather` 與 release 簽章金鑰的 SHA-1（`keytool -list -v -keystore release.jks`）
2. 「API 限制」只勾選 **Maps SDK for Android** 與 **Routes API**

金鑰會包含在 APK 內，上述限制可以避免被其他 App 盜用。

### 移動式測速回報的 Firebase 設定

移動式測速回報使用與 Crashlytics 同一個 Firebase 專案（`GOOGLE_SERVICES_JSON`），另外要在 Firebase 主控台：
1. **Authentication** → 登入方式 → 啟用「匿名」（可以勾選 30 天後自動刪除匿名帳號；App 發現帳號被刪會自動重新建立）
2. **Firestore Database** → 建立資料庫，資料庫 ID 填 **`weather`**（App 連這個 ID，見 `CameraReportRepository.DATABASE_ID`；地區建議 `asia-east1` 台灣）
3. Firestore → 選 `weather` 資料庫 → **規則**：貼上 repo 根目錄的 [`firestore.rules`](firestore.rules) 並發布（只允許登入者讀取、新增自己的回報，座標限台灣附近、2.5 小時內失效）
4. （選用）Firestore → **TTL 政策**：集合 `camera_reports`、欄位 `expiresAt`，讓失效的回報自動刪除

回報成功後，`weather` 資料庫會出現 `camera_reports` 集合。沒有 `GOOGLE_SERVICES_JSON` 時不顯示「回報測速」按鈕，只提醒固定式測速照相。

Fork 這個專案自行建置時，沒有這些 Secrets 也能編譯，只是會用臨時的 debug 金鑰簽章，也不會發佈。

## 本機建置

```bash
./gradlew testDebugUnitTest assembleDebug   # 需要 JDK 17 與 Android SDK
```

## 授權

程式碼以 [MIT License](LICENSE) 授權。`app/src/main/assets/` 與測試資料中的內建資料，依上方「資料來源與授權」表格中各來源的授權條款使用（例如 GeoNames 需標示出處）。
