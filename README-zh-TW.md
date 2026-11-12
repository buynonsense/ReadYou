<div align="center">
    <img width="200" height="200" style="display: block; border: 1px solid #f5f5f5; border-radius: 9999px;" src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/en-US/images/icon.png">
</div>

<br>
<br>
<br>

<div align="center">
    <img alt="GitHub" src="https://img.shields.io/github/license/ReadYouApp/ReadYou?color=c3e7ff&style=flat-square">
    <a target="_blank" href="https://github.com/ReadYouApp/ReadYou/releases">
        <img alt="Version" src="https://img.shields.io/github/v/release/ReadYouApp/ReadYou?color=c3e7ff&label=version&style=flat-square">
    </a>
    <img alt="GitHub last commit" src="https://img.shields.io/github/last-commit/ReadYouApp/ReadYou?color=c3e7ff&style=flat-square">
    <br>
    <a target="_blank" href="https://t.me/ReadYouApp">
        <img alt="Telegram" src="https://img.shields.io/badge/Telegram-ReadYouApp-c3e7ff?logo=telegram&style=flat-square">
    </a>
    <a target="_blank" href="https://www.figma.com/file/ViBW8GbUgkTMmK6a80h8X1/Read-You?node-id=7028%3A23673">
        <img alt="Figma" src="https://img.shields.io/badge/Figma-ReadYou-c3e7ff?logo=figma&style=flat-square">
    </a>
</div>

<div align="center">
    <h1>Read You</h1>
    <p>一款以 <a target="_blank" href="https://m3.material.io/">Material You</a> 風格呈現的 Android RSS 閱讀器。</p>
    <p><a target="_blank" href="https://github.com/ReadYouApp/ReadYou/blob/main/README.md">English</a>&nbsp;&nbsp;|&nbsp;&nbsp;
    <a target="_blank" href="https://github.com/ReadYouApp/ReadYou/blob/main/README-de.md">Deutsch</a>&nbsp;&nbsp;|&nbsp;&nbsp;
    <a target="_blank" href="https://github.com/ReadYouApp/ReadYou/blob/main/README-zh-CN.md">简体中文</a>&nbsp;&nbsp;|&nbsp;&nbsp;
    <a target="_blank" href="https://github.com/ReadYouApp/ReadYou/blob/main/README-zh-TW.md">繁體中文</a>&nbsp;&nbsp;|&nbsp;&nbsp;
    <a target="_blank" href="https://github.com/ReadYouApp/ReadYou/blob/main/README-fa.md">فارسی (Outdated)</a></p>
    <br/>
    <br/>
    <img src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/en-US/images/phoneScreenshots/startup.png" width="19.2%" alt="startup" />
    <img src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/en-US/images/phoneScreenshots/feeds.png" width="19.2%" alt="feeds" />
    <img src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/en-US/images/phoneScreenshots/flow.png" width="19.2%" alt="flow" />
    <img src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/en-US/images/phoneScreenshots/read.png" width="19.2%" alt="read" />
    <img src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/en-US/images/phoneScreenshots/settings.png" width="19.2%" alt="settings" />
    <br/>
    <br/>
</div>

## 功能特性

**Read You** 是一款以 [Material You](https://m3.material.io/) 風格呈現的 Android RSS 閱讀器。

以下是目前已完成的進展，以及近期將努力實現的目標：

- [x] 訂閱 RSS 連結
- [x] 匯入或匯出 OPML 文件
- [x] 新文章通知
- [x] 文章閱讀體驗優化
- [x] 原始文章全文解析
- [x] 多帳戶支援
- [x] 朗讀文章
- [x] Android 桌面小工具
- [ ] ...

## 第三方服務整合

**Read You** 整合了多個第三方服務 API，讓您可以使用現有的雲端帳戶作為資料來源。

- [x] Fever
- [x] Google Reader
- [x] FreshRSS
- [x] Miniflux
- [x] Tiny Tiny RSS
- [x] Inoreader
- [x] Feedly
- [ ] Feedbin
- [ ] ...

### 同步與復原設定

- Feedly、Inoreader 使用具有讀寫權限的 API token，Inoreader 亦可填寫 AppId / AppKey；Miniflux 支援 token 或帳號密碼；Tiny Tiny RSS 需要伺服器位址、帳號密碼並啟用 API。憑證到期可在帳號設定重新連線。這四類服務的訂閱管理在各自網頁端完成。
- 已讀與收藏操作儲存在本機持久佇列，離線後會重試。帳號設定顯示同步進度、最後成功時間及錯誤。完整更新成功後移除已取消的訂閱，並保留收藏和稍後閱讀文章。
- 本機 RSS 帳號可透過相同的 WebDAV 伺服器、目錄及遠端帳號 ID 配對，遇到衝突時選擇版本。復原支援合併或完全取代；保留 20 個遠端歷史版本及三份本機復原副本，可選擇包含離線全文。伺服器帳號可上傳備份，請在本機 RSS 帳號復原。
- 搜尋涵蓋標題、摘要、RSS 內文與離線全文；Widget 依資料所屬帳號開啟。密碼由 Android Keystore 加密且不會包含在備份中。HTTP 須明確啟用，私人憑證須匯入並保留主機名稱驗證。

新服務整合仍為實驗功能：自動測試涵蓋協定、認證失敗、分頁與狀態寫回，仍待真實帳號驗證。詳見[驗收紀錄](docs/improvement-plan.md)。API 存取亦取決於服務方案與開發者權限。

## 下載

[<img src="https://s1.ax1x.com/2023/01/12/pSu1a36.png" alt="Get it on GitHub" height="80">](https://github.com/ReadYouApp/ReadYou/releases)
[<img src="https://s1.ax1x.com/2023/01/12/pSnTZ0f.png"
     alt="Get it on Telegram Channel"
     height="80">](https://t.me/ReadYouApp)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/me.ash.reader/)

F-Droid 版本無法升級至其他發布渠道的版本，其編譯、簽署及發布均由 [F-Droid](https://f-droid.org/docs/FAQ_-_General/) 統一管理。

## 每夜構建版本（Nightly）

我們提供 Nightly 版本供測試使用。這些版本包含最新的功能與改進，但穩定性可能不及正式版本。請注意，Nightly 構建可能存在 Bug，不建議作為日常使用版本。

您可以透過以下連結下載 Nightly 版本：

<a target="_blank" href="https://github.com/ReadYouApp/ReadYou/actions/workflows/build_commit.yaml">查看 Nightly 構建</a>

在 GitHub Actions 頁面選擇最新的工作流程，並從工作流程摘要頁面下載 Artifacts（可能需要登入 GitHub）。

**在試用 Nightly 版本前，請務必備份您的資料以防萬一。**

## 贊助

**Read You** 是一款免費的開源軟體，受益於開源社群的支持，每位使用者均可免費享用其完整功能。如果您喜歡我目前的工作，歡迎請我喝杯咖啡。☕️

[<img src="https://s1.ax1x.com/2023/01/12/pSnHqpQ.png" alt="donate" height="80">](https://ash7.io/sponsor)

感謝所有的愛與支持。❤️

## 本地化翻譯

感謝每一位 **Read You** 的翻譯貢獻者。如果您也想參與貢獻，請透過 [Weblate](https://hosted.weblate.org/engage/readyou/) 提交翻譯。

[<img src="https://hosted.weblate.org/widgets/readyou/-/horizontal-auto.svg" alt="" />](https://hosted.weblate.org/engage/readyou/)

## 構建

> 歡迎提交 [Pull Request](https://github.com/ReadYouApp/ReadYou/pulls)。[GitHub Actions](https://github.com/ReadYouApp/ReadYou/actions) 會自動為每次提交打包所有版本的 APK 文件。

**Read You** 基於 Android 原生的 [Jetpack Compose](https://developer.android.com/jetpack/compose) 工具包構建。

1. 首先需要取得 **Read You** 的原始碼：

   ```shell
   git clone https://github.com/ReadYouApp/ReadYou.git
   ```

2. 然後透過 [Android Studio（最新版本）](https://developer.android.com/studio) 開啟。

3. 點擊 `▶ 運行（Run）` 按鈕後將會自動構建並運行。

    > 如遇卡頓現象，請選擇 Release 版本構建。

## 致謝

### 開源專案

- [MusicYou](https://github.com/Kyant0/MusicYou)
- [ParseRSS](https://github.com/muhrifqii/ParseRSS)
- [Readability4J](https://github.com/dankito/Readability4J)
- [opml-parser](https://github.com/mdewilde/opml-parser)
- [compose-html](https://github.com/ireward/compose-html)
- [Rome](https://github.com/rometools/rome)
- [Feeder](https://gitlab.com/spacecowboy/Feeder)
- [Seal](https://github.com/JunkFood02/Seal)
- [news-flash](https://gitlab.com/news-flash)
- [besticon](https://github.com/mat/besticon)
- [Jiffy Reader](https://github.com/ansh/jiffyreader.com)
- ...

### 特別感謝

[<img src="https://avatars.githubusercontent.com/u/76829190?v=4" width="180" height="180" style="display: block; border: 1px solid #f5f5f5; border-radius: 9999px;"/>](https://github.com/Kyant0)

感謝 **@Kyant0** 為 **Read You** 提供的設計靈感與 Monet 引擎實作。

[<img src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.png" width="200" alt="Copyright © 2000-2023 JetBrains s.r.o. JetBrains and the JetBrains logo are registered trademarks of JetBrains s.r.o."/>](https://www.jetbrains.com/)

感謝 **JetBrains** 為 **Read You** 提供免費的開源 IDE 授權。

[<img src="https://hosted.weblate.org/widgets/readyou/-/287x66-white.png" width="200"/>](https://hosted.weblate.org/engage/readyou/)

感謝 **Weblate** 為 **Read You** 提供免費的開源專案翻譯託管服務。

## 許可證

GNU GPL v3.0 © [Read You](https://github.com/ReadYouApp/ReadYou/blob/main/LICENSE)
