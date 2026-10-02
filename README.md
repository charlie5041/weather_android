# 我的天氣（iPhone 風格 Android 天氣 App）

以 Kotlin + Jetpack Compose 打造、仿 iOS 天氣介面的 Android App。

## 功能

- 背景漸層隨天氣與日夜變化，有下雨、下雪、星空動畫
- 大字顯示目前溫度、天氣狀況、今日高低溫，往下捲動時標題自動收合
- 24 小時逐時預報（含降雨機率與日出、日落時間點）
- 10 日預報，附 iOS 風格的溫度區間色條與目前溫度圓點
- 資訊卡：空氣品質（AQI、PM2.5、PM10）、紫外線、日出日落軌跡、風（指南針）、降雨量、體感溫度、濕度／露點、能見度、氣壓、雲量
- 支援目前位置與多個城市，左右滑動切換；在城市列表搜尋新增，長按可刪除
- 下拉重新整理；離線時顯示上次快取的資料
- **中央氣象署資料（台灣地區自動啟用）**：最近測站即時觀測、鄉鎮逐時／一週預報、天氣特報
- **降雨與衛星地圖**（底部左側 🗺️）：氣象署雷達回波與向日葵衛星雲圖動畫（近 2～3 小時），並標出目前城市位置

## 資料來源與準確度

| 資料 | 台灣地區 | 其他地區 |
| --- | --- | --- |
| 目前天氣 | 中央氣象署最近測站實測（10 公里內，每 10 分鐘更新） | Open-Meteo |
| 逐時預報（3 天） | 中央氣象署鄉鎮預報：逐時溫度、3 小時降雨機率與天氣現象 | Open-Meteo |
| 每日預報 | 前 7 天：中央氣象署鄉鎮一週預報；第 8～10 天：Open-Meteo | Open-Meteo |
| 天氣特報 | 中央氣象署（所在縣市） | — |
| 空氣品質、紫外線、日出日落 | Open-Meteo | Open-Meteo |
| 雷達、衛星雲圖 | 中央氣象署 | — |

氣象署資料取自[氣象資料開放平臺](https://opendata.cwa.gov.tw/)的公開檔案與氣象署網站圖資，**不需要申請授權碼**。
[Open-Meteo](https://open-meteo.com/) 的 `best_match` 會依地區自動混合解析度最高的數值模式（ECMWF、JMA、GFS 等）。

氣象署資料的解析與合併邏輯有單元測試（`app/src/test`），CI 每次建置都會執行。

## CI/CD：自動安裝到手機

`.github/workflows/android.yml` 每次 push 都會建置 release APK：

| 事件 | 結果 |
| --- | --- |
| push 任何分支／PR | 建置 APK，上傳為 Actions artifact |
| push 到 `main` | 發佈到 Firebase App Tester，並發佈 GitHub Release（tag 為 `v1.0.<run_number>`） |
| 手動執行（workflow_dispatch） | 可選擇是否發佈到 App Tester 與 Release |

版本號就是 GitHub Actions 的 run number，新版可以直接覆蓋安裝。

### 1. 設定簽章金鑰（只需做一次）

Android 要求同一個 App 每次更新都用同一把金鑰簽章。請在自己電腦上產生金鑰（請妥善保存，不要 commit 進 repo）：

```bash
keytool -genkeypair -keystore my-weather.jks -alias weather -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 my-weather.jks   # macOS 用：base64 -i my-weather.jks
```

到 GitHub repo → Settings → Secrets and variables → Actions，新增：

| Secret | 內容 |
| --- | --- |
| `KEYSTORE_BASE64` | 上面 base64 指令的輸出 |
| `KEYSTORE_PASSWORD` | keystore 密碼 |
| `KEY_ALIAS` | `weather` |
| `KEY_PASSWORD` | key 密碼（PKCS12 時與 keystore 密碼相同） |

還沒設定時，CI 會改用臨時的 debug 金鑰，APK 可以安裝，但每次更新都得先解除安裝。

### 2. 手機自動更新：Firebase App Tester

每次 push 到 `main`，CI 會把 APK 上傳到 Firebase App Distribution，手機上的 **App Tester** 會收到通知，點一下就能更新。

**Firebase 設定（只需做一次）**

1. 到 [Firebase Console](https://console.firebase.google.com/) 建立專案（不需要 Google Analytics）。
2. 專案總覽 → 新增應用程式 → Android，套件名稱填 `com.charlie.weather`，其他欄位可以略過。
   完成後在「專案設定 → 一般」複製 **App ID**（格式像 `1:1234567890:android:abcdef...`）。
3. 左側選單 → Release & Monitor → **App Distribution** → 開始使用。
4. 建立服務帳戶：專案設定 → 服務帳戶 → 「管理服務帳戶權限」（會開啟 Google Cloud Console）
   → 建立服務帳戶，角色選 **Firebase App Distribution Admin** → 建立後到「金鑰」分頁 → 新增金鑰 → JSON，下載檔案。

**GitHub Secrets**（repo → Settings → Secrets and variables → Actions）

| Secret | 內容 |
| --- | --- |
| `FIREBASE_APP_ID` | 第 2 步複製的 App ID |
| `FIREBASE_SERVICE_ACCOUNT` | 第 4 步下載的 JSON 檔「整份內容」 |
| `FIREBASE_TESTERS` | 要收到更新的 Google 帳號 email，多個用逗號分隔 |

**手機端**

1. 第一次發佈後，手機上的 Google 帳號會收到 Firebase 邀請信，點「開始使用」接受邀請。
2. 依照指示安裝 **App Tester**（Firebase 的測試版安裝 App）並登入同一個帳號。
3. 之後每次有新版，App Tester 會推播通知，點「下載」→「安裝」即可。
   vivo 第一次會詢問是否允許 App Tester 安裝應用程式，請允許。

（GitHub Release 仍然會同步發佈，也可以直接從 Release 頁面下載 APK。）

## 本機建置

```bash
./gradlew assembleDebug      # 需要 JDK 17 與 Android SDK
```
