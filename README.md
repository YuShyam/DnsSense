# DnsSense

一個幫 Android 自動切換「私人 DNS」的小工具。

平常在系統裡設定私人 DNS（例如 `dns.adguard-dns.com`）很好用，但在公司內網、飯店、咖啡廳或特定行動網路下，常會遇到 TCP 853 被擋、跳不出網頁認證（Captive Portal），或是某些網站功能打不開，最後只好手動進設定切回「自動」。`DnsSense` 會在背景監聽連線狀態，遇到排除清單內的網路或探測不到 853 時，自動切回系統的自動 DNS（`opportunistic`），離開該網路後再幫你切回私人 DNS。

## 功能說明

- **手動排除清單（優先判斷）**：可指定特定 Wi-Fi 名稱（SSID）或行動數據（4G / 5G），連上時直接切為系統自動 DNS。適合公司內網、家用區網，或 853 沒被擋但部分功能會異常的網路。
- **DoT 853 存活探測**：不在排除清單的網路，會實際對設定的私人 DNS 主機發起 TCP 853 TLS 連線與 DNS 查詢，並檢查系統的 `CAPTIVE_PORTAL` 狀態；不通或需要網頁登入時自動退回自動 DNS。
- **下拉快速設定磚**：偶爾遇到單一網頁被擋廣告規則誤殺時，下拉點一下即可讓「本次連線」暫時改用自動 DNS，離開該網路後自動恢復。
- **兩種運作模式**：
  - **自動模式**：授予一次 `WRITE_SECURE_SETTINGS` 權限即可在背景直接修改系統設定。免 Root、不需要 Shizuku 常駐，重開機後權限依然有效，也不會佔用 VPN 通道。
  - **提醒模式**：未授權時一樣會在背景偵測，需要切換時發送通知，點一下直接開啟系統網路設定頁讓你手動切。

## 啟用自動模式（一次性授權）

以下兩種方式擇一執行一次即可，完成後就算關閉或移除 Shizuku 也不會失效。

### 方式一：透過 Shizuku（免接電腦）

1. 先啟動 Shizuku 並同意授權給 `DnsSense`。
2. 打開 `DnsSense`，切到「設定」頁籤，點擊「Shizuku 一鍵授權」。

### 方式二：透過電腦 ADB

```bash
adb shell pm grant com.yushyam.dnssense android.permission.WRITE_SECURE_SETTINGS
```

> 備註：Android 10 以上系統規定，背景讀取 Wi-Fi 名稱必須開啟「一律允許精確位置」權限。本工具完全在本地執行，不會上傳任何位置或連線資訊。

## 專案目錄

- `android/`：Android 原始碼（Kotlin / Jetpack Compose）
- `docs/ARCHITECTURE.md`：決策流程與探測實作筆記
- `.github/workflows/build-apk.yml`：GitHub Actions 單元測試與 APK 建置設定

## 授權

[MIT License](LICENSE)
