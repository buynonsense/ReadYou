<div align="center">
    <img width="200" height="200" style="display: block; border: 1px solid #f5f5f5; border-radius: 9999px;" src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/zh-CN/images/icon.png">
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
    <p>一个以 <a target="_blank" href="https://m3.material.io/">Material You</a> 风格呈现的 Android RSS 阅读器。</p>
    <a target="_blank" href="https://github.com/ReadYouApp/ReadYou/blob/main/README.md">English</a>&nbsp;&nbsp;|&nbsp;&nbsp;
    <a target="_blank" href="https://github.com/ReadYouApp/ReadYou/blob/main/README-de.md">Deutsch</a>&nbsp;&nbsp;|&nbsp;&nbsp;
    简体中文&nbsp;&nbsp;|&nbsp;&nbsp;
    <a target="_blank" href="https://github.com/ReadYouApp/ReadYou/blob/main/README-zh-TW.md">繁體中文</a>&nbsp;&nbsp;|&nbsp;&nbsp;
    <a target="_blank" href="https://github.com/ReadYouApp/ReadYou/blob/main/README-fa.md">فارسی</a></p>
    <br/>
    <br/>
    <img src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/zh-CN/images/phoneScreenshots/startup.png" width="19.2%"alt="startup" />
    <img src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/zh-CN/images/phoneScreenshots/feeds.png" width="19.2%" alt="feeds" />
    <img src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/zh-CN/images/phoneScreenshots/flow.png" width="19.2%" alt="flow" />
    <img src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/zh-CN/images/phoneScreenshots/read.png" width="19.2%" alt="read" />
    <img src="https://raw.githubusercontent.com/ReadYouApp/ReadYou/main/fastlane/metadata/android/zh-CN/images/phoneScreenshots/settings.png" width="19.2%" alt="settings" />
    <br/>
    <br/>
</div>

## 特性

**Read You** 是一个以 [Material You](https://m3.material.io/) 风格呈现的 Android RSS 阅读器，这些是目前已完成的功能和未来要做的事。

- [x] 订阅 RSS 链接
- [x] 导入 / 导出 OPML 文件
- [x] 新文章通知
- [x] 可读性优化（截取文章正文内容）
- [x] 全文解析
- [x] 支持多账户
- [x] 仿生阅读
- [x] 大声朗读
- [x] Android 微件 / 小组件
- [ ] ...

## 集成

**Read You** 也集成了一些第三方服务 API，支持您使用已有的云端账户来作为数据源。

- [x] Fever
- [x] Google Reader
- [x] FreshRSS
- [x] Miniflux
- [x] Tiny Tiny RSS
- [x] Inoreader
- [x] Feedly
- [ ] Feedbin
- [ ] ...

### 同步与恢复设置

- Feedly、Inoreader 使用具备读写权限的 API token，Inoreader 还可填写 AppId / AppKey；Miniflux 支持 token 或账号密码；Tiny Tiny RSS 需要服务器地址、账号密码并开启 API。凭据过期后可在账号设置中重新连接。这四类服务的订阅管理在各自网页端完成。
- 已读和收藏操作写入本地持久队列，断网后会重试。账号设置显示同步进度、最后成功时间和失败原因。完整刷新成功后清理服务端已移除的订阅，同时保留收藏和稍后读文章。
- 在各设备填写相同的 WebDAV 服务器、登录信息和远程目录，即可同步同一份本地 RSS 数据，无需填写配对 ID。日常同步订阅源、分组、APP 设置及已读 / 收藏 / 稍后读状态，不上传文章内容。文章正文与离线全文仅在独立的手动备份中按需启用，默认关闭。清理本机缓存不会删除其他设备的文章。恢复支持合并或替换，保留 20 个备份历史版本和三份本地恢复副本。旧备份可恢复；各设备须一同更新以使用新同步格式。服务端账号支持手动备份，请在本地 RSS 中恢复。
- 搜索覆盖标题、摘要、RSS 正文和离线全文；Widget 按文章或订阅源所属账号打开。凭据由 Android Keystore 加密，备份不包含密码和 token；HTTP 须为服务器显式开启，私有证书须导入且仍检查主机名。

新接入服务处于实验阶段：自动化测试覆盖协议、认证失败、分页和状态写回，仍需真实账号验证兼容性。测试证据和已知边界见[改进验收记录](docs/improvement-plan.md)。API token 可用性取决于服务套餐与开发者权限。

## 下载

[<img src="https://s1.ax1x.com/2023/01/12/pSu1a36.png" alt="Get it on GitHub" height="80">](https://github.com/ReadYouApp/ReadYou/releases)
[<img src="https://s1.ax1x.com/2023/01/12/pSnTZ0f.png"
     alt="Get it on Telegram Channel"
     height="80">](https://t.me/ReadYouApp)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/me.ash.reader/)

F-Droid 版本与其他版本不兼容，您无法通过覆盖安装包来升级、切换至其他版本，需要卸载后再重新安装，届时所有本地数据将被清除。整个应用的编译、签名和分发均由 [F-Droid](https://f-droid.org/docs/FAQ_-_General/) 统一完成。

## 夜间构建

我们提供夜间构建用于测试目的，这些版本包含最新的功能和改进，但可能不如常规版本稳定。请注意，夜间版本可能存在 bug，并且不建议日常使用。

<a target="_blank" href="https://github.com/ReadYouApp/ReadYou/actions/workflows/build_commit.yaml">检查夜间构建</a>

从 GitHub Actions 页面选择最新的 workflow，并从该 workflow 的摘要页面下载构建文件（您可能需要登录 GitHub）。

**在尝试夜间版本之前，请备份好您的数据。**

## 捐赠

**Read You** 是一款免费的自由开源软件，它受益于开源社区，每一位用户都能够免费享受到它完整的功能，如果您赞赏我目前的工作，可以选择请我喝一杯咖啡 ☕️。

[<img src="https://s1.ax1x.com/2023/01/12/pSnHqpQ.png"
     alt="donate"
     height="80">](https://ash7.io/sponsor)

[感谢所有的爱与支持 ❤️](https://afdian.com/a/ashinch?tab=sponsor)

## 本地化

感谢每一位 **Read You** 的翻译人员，如果您也希望做出贡献，请通过 [Weblate](https://hosted.weblate.org/engage/readyou/) 来提交翻译文本。

[<img src="https://hosted.weblate.org/widgets/readyou/-/horizontal-auto.svg" alt="" />](https://hosted.weblate.org/engage/readyou/)

## 构建

> 欢迎 [提交](https://github.com/ReadYouApp/ReadYou/pulls) 您的代码，[GitHub Actions](https://github.com/ReadYouApp/ReadYou/actions) 为每次提交都自动打包了所有分发渠道的 APK 文件。

**Read You** 基于 [Jetpack Compose](https://developer.android.com/jetpack/compose) 工具包来构建 Android 原生用户界面。

1. 首先您需要获取 **Read You** 的源代码：

    ```shell
    git clone https://github.com/ReadYouApp/ReadYou.git
    ```

2. 然后通过 [Android Studio (最新版本)](https://developer.android.com/studio) 打开。

3. 点击 `▶ 运行（Run）` 按钮后将会自动构建并运行。

    > 如遇卡顿现象，请选择 Release 版本构建。

## 致谢

### 开源项目

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

### 特别鸣谢

[<img src="https://avatars.githubusercontent.com/u/76829190?v=4" width="180" height="180" style="display: block; border: 1px solid #f5f5f5; border-radius: 9999px;"/>](https://github.com/Kyant0)

感谢 **@Kyant0** 为 **Read You** 提供的设计灵感与 Monet 引擎实现。

[<img src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.png" width="200" alt="Copyright © 2000-2023 JetBrains s.r.o. JetBrains and the JetBrains logo are registered trademarks of JetBrains s.r.o."/>](https://www.jetbrains.com/)

感谢 **JetBrains** 为 **Read You** 提供的开源项目产品许可证。

[<img src="https://hosted.weblate.org/widgets/readyou/-/287x66-white.png"  width="200"/>](https://hosted.weblate.org/engage/readyou/)

感谢 **Weblate** 为 **Read You** 提供的开源项目免费托管服务。

## 许可证

GNU GPL v3.0 © [Read You](https://github.com/ReadYouApp/ReadYou/blob/main/LICENSE)
