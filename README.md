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

## 資料來源與準確度

天氣資料來自 [Open-Meteo](https://open-meteo.com/)，免費、不需金鑰。預設的 `best_match` 會依所在地區，自動混合解析度最高的數值模式
（例如台灣附近會用到 JMA、ECMWF、GFS 等），通常比手機內建天氣更準。

## CI/CD：自動安裝到手機

`.github/workflows/android.yml` 每次 push 都會建置 release APK：

| 事件 | 結果 |
| --- | --- |
| push 任何分支／PR | 建置 APK，上傳為 Actions artifact |
| push 到 `main` | 另外發佈 GitHub Release（tag 為 `v1.0.<run_number>`） |
| 手動執行（workflow_dispatch） | 可選擇是否發佈 Release |

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

### 2. 手機自動更新（推薦用 Obtainium）

1. 在手機安裝 [Obtainium](https://github.com/ImranR98/Obtainium/releases)（開源的 App 更新器）。
2. 因為這個 repo 是 **private**，請到 GitHub → Settings → Developer settings → Fine-grained tokens，
   建立一個只對 `weather_android` 開放 **Contents: Read-only** 權限的 token。
3. 在 Obtainium → 設定 → GitHub 憑證，貼上 token。
4. 在 Obtainium → 新增 App，輸入 `https://github.com/charlie5041/weather_android`，新增後安裝。
5. 之後每次 push 到 `main`，Obtainium 就會通知更新，點一下即可安裝（也能設定背景自動檢查）。

## 本機建置

```bash
./gradlew assembleDebug      # 需要 JDK 17 與 Android SDK
```
