# 项目改进与验收清单

基于 2026-11-12 对 v0.17.0 的审查。保留原有数据，迁移已有配置与凭据；每个阶段通过对应回归测试后再标记完成。

## 1. 账号与后台任务

- [x] Worker、RSS API、全文缓存、清理均显式绑定账号，切换前台账号不改变后台任务目标。
- [x] 每个账号独立安排、取消同步；失败不执行成功后处理；删除账号取消相关工作。
- [x] 修复账号 ID / 类型 ID 混用、凭据缓存失效及通知 / Widget 跨账号打开。
- [x] 账号显示同步进度、最后成功时间、失败原因与重试入口。

## 2. 数据清理

- [x] 删除与归档记录在同一事务完成，精确返回删除项并清理对应缓存。
- [x] 归档身份唯一且有索引，迁移去重；保护星标、稍后读，设置按账号保存。
- [x] 恢复保留文章时间，清理不会与 WebDAV / RSS 同步互相复活文章。
- [x] 统计与预览正确，异常恢复 UI 状态，页面任务遵循生命周期。

## 3. 安全

- [x] 默认系统 TLS / 主机名校验，自签名证书使用明确的服务器级信任。
- [x] 凭据迁移为 Android Keystore + AES-GCM；失败明确提示，禁止静默清空。
- [x] 备份排除凭据，明文服务使用显式配置并在 UI 中说明。

## 4. WebDAV

- [x] 配置、同步时间、远程账号身份隔离；迁移旧配置，不自动混入另一个账号。
- [x] 严格区分无备份、备份损坏、不支持版本、连接失败，禁止损坏后覆盖。
- [x] 版本 / ETag 条件写入、原子发布、校验和、历史备份与回滚。
- [x] 流式压缩、分块 / 增量上传，避免全量正文多份驻留与逐源查询。
- [x] 可预览冲突、明确选择解决方式；同步删除状态与分组 / 订阅源变更。
- [x] 合并恢复 / 完全替换，恢复前本地备份，可选全文缓存备份。
- [x] 同步历史、错误详情、重试、进度，后台错误可见。

## 5. 阅读与服务

- [x] 修复 Widget 未实现方法、Flow 重建、配置与跨账号行为。
- [x] FTS 搜索覆盖标题、摘要、RSS 正文与已缓存全文，保留筛选和排序。
- [x] 完成 Feedly / Inoreader 账号配置、认证、同步与阅读状态写回。
- [x] 补齐 Miniflux / Tiny Tiny RSS 等审查提及的服务接入与验证。
- [x] 更新各语言 README 的功能实现状态及设置说明。

## 6. 验证

- [x] 账号切换、同步失败、WebDAV 损坏 / 并发 / 大数据、恢复 / 清理 / 迁移回归测试。
- [x] 单元测试、数据库 / Worker 测试与构建通过。
- [x] 修复 lint errors（含 Compose Flow / 资源访问和复数资源），必要警告按影响修复。
- [x] 审核每项需求的实际文件、测试和运行证据；仍需真实服务或设备验证的内容明确记录。

## 执行记录

- 初始状态：`dev`，工作区干净；缺少 CodeGraph 索引。现有报告为 11 个单元测试通过，lint 37 errors / 208 warnings。开始重新验证。
- 账号缓存 / 类型混用的两个回归测试先失败，修复后通过。清理事务测试涵盖稍后读、账号边界、归档失败回滚；真实 7→8 数据库迁移测试验证数据保留、归档去重与唯一索引。
- 2026-11-12：`testGithubReleaseUnitTest` 全量 **33 项通过**（日志 `/private/tmp/readyou-safety-tests-5.log`）。新增覆盖账号导航与 Intent 一次性消费、账号锁、WebDAV 配置隔离和旧配置归属、损坏备份拒绝、加密随机 nonce / 篡改拒绝 / 旧 DES 兼容、TLS / HTTP 真实握手。
- 凭据使用 Keystore AES-GCM；测试中的加密算法使用注入测试密钥。仍需 Android 设备验证 Keystore 存取及升级重启行为，不能以 JVM 算法测试代替。
- 安全设置按完整服务器 origin 保存。HTTP 默认拒绝，需要用户为此服务器开启；私有证书需导入通过可信渠道取得的 PEM，继续校验主机名。系统备份排除数据库和包含凭据的配置，应用设置导出也排除 WebDAV 凭据。
- WebDAV 当前已完成账号配置 / 远程身份隔离、旧配置迁移、损坏保护与页面生命周期处理；v2 条件写入、分块历史、冲突预览及恢复仍待完成，未标记整项验收。

## 2026-11-12 本轮实现与证据

- 全量 `testGithubReleaseUnitTest --no-daemon --offline --max-workers=2`：**63 tests，0 failures，0 errors，0 skipped**。测试 XML 位于 `app/build/test-results/testGithubReleaseUnitTest/`；本次构建日志 `/private/tmp/readyou-final-checks-2.log`。
- 阅读状态：Room v10 的 `pending_article_state` 持久队列，将本地已读 / 收藏操作与待上传意图同事务提交；按 revision 确认，旧请求不能删除新的切换。失败重试、重建对象、账号边界、批量已读与收藏由 `ArticleStateOutboxTest` 验证。
- 账号边界：后台同步、全文缓存、清理、WebDAV 持有账号锁；Google Reader / Fever API 显式绑定账号。订阅、OPML 与分组操作使用数据所属账号，配置更新基于事务内最新账号数据。相同服务类型账号之间切换不会复用上一个账号的分组和计数，见 `GroupWithFeedsListUseCaseTest`。
- 搜索：FTS4 覆盖标题、摘要、RSS 正文和已缓存全文；使用兼容 SQLite 的隐式交集查询，筛选和排序保持有效，见 `ArticleSearchDatabaseTest`。
- 服务接入：Feedly / Inoreader token、Miniflux token 或 Basic、Tiny Tiny RSS session 登录与过期重登；完整分页及已读 / 收藏写回。Miniflux 旧版 bookmark 接口使用读取、条件切换和结果校验。`RemoteReaderClientTest` 验证协议；`RemoteRssServiceTest` 验证完整分页成功后才清理已移除的订阅，同时保护收藏、稍后读与其他账号。未返回的旧文章不被猜测为已读或未收藏。
- WebDAV v2：稳定身份、删除 tombstone、三方合并、压缩分块、校验和、强 ETag 条件发布、历史版本、恢复前本地副本。页面提供冲突预览和处理、合并 / 替换确认、历史回退、全文开关、进度、错误详情及重试。本地恢复副本无需联网。
- `WebDavBackupEngineTest` 覆盖清理删除双向传播、冲突前不改两端、并发发布拒绝、损坏拒绝、恢复时间与全文、本地离线恢复、历史选择、600 篇正文分块复用与恢复、上传期间本地修改保护和 staging 差异检测。`WebDavSyncViewModelTest` 验证冲突处理后按原账号和选择重试。
- WebDAV 与服务端账号：服务端账号可上传备份；双向 WebDAV 同步及恢复限定本地 RSS 账号，避免构造无法与服务端对应的订阅和文章身份。后台 WebDAV 错误显示在账号设置，也保存在操作历史。
- 英文、简中、繁中、日文、德文和波斯文 README 已更新功能状态、接入方法和限制。

## 最终构建验收

- `./gradlew testGithubReleaseUnitTest lintGithubRelease assembleGithubDebug assembleGithubDebugAndroidTest --no-daemon --offline --max-workers=2`：**BUILD SUCCESSFUL**，63 项测试全部通过。日志：`/private/tmp/readyou-final-checks-3.log`。
- 修复 `SyncWorker` 对 WorkManager 内部 `Result.Success` / `Result.Failure` 类型的访问，使用公开的结果工厂和输出数据；成功判断保留非空输出数据的语义。
- `lintGithubRelease`：**0 errors、170 warnings、4 hints**，未新增 baseline 或禁用检查。报告：`app/build/reports/lint-results-githubRelease.html`。剩余警告主要为未使用资源、旧依赖、日志与混用 Material 组件；还包含旧崩溃处理、CursorWindow 私有反射等应另行改进的代码，不代表零警告验收。
- 本次可安装调试包：`app/build/outputs/apk/github/debug/ReadYou-0.17.0-4892fd0f.apk`；设备测试包：`app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk`。已安装到 `emulator-5580`。
- API 34 独立模拟器上 **8 项设备测试全部通过**：`CredentialDeviceTest` 的 4 项、`AccountNavigationDeviceTest`、`ArticlePagingListMemoryTest`、`NotificationMemoryTest`、`ExampleInstrumentedTest`。凭据写入和重启读取使用两次独立 instrumentation 调用，中间显式 `am force-stop me.ash.reader`；篡改与密钥丢失均报错且保留原密文。设备日志：`/private/tmp/readyou-device-credential-write.log`、`/private/tmp/readyou-device-credential-restart.log`、`/private/tmp/readyou-device-credential-recovery.log`、`/private/tmp/readyou-device-navigation-memory.log`。
- 分页测试切换 10 次：`initialJobs=2`、`currentJobs=2`、`oldArticleRetained=false`。通知测试实际写入 10,050 篇未读文章，通过通知 PendingIntent 打开并验证正文；测得 Java heap 为 20,965,024 bytes，最大 Java heap 201,326,592 bytes，PSS 317,629 KiB。该单次模拟器测量不等于硬件设备或长期使用的内存上界，见 `/private/tmp/readyou-device-navigation-logcat.log`。
- 冷启动 smoke 通过：`am start -W` 返回 `Status: ok`、`LaunchState: COLD`，正常显示首次启动欢迎页。启动日志：`/private/tmp/readyou-device-startup.log`；截图：`/private/tmp/readyou-validation-startup.png`。`git diff --check` 通过。验收后已关闭本任务的临时模拟器，保留 AVD 数据和日志。

### 需求与回归证据对应

| 需求 | 主要实现 | 回归证据 |
| --- | --- | --- |
| 账号与后台隔离 | `AccountService`、`RssService`、`SyncWorker`、`AccountOperationLock`、`ReaderCacheHelper` | `AccountViewModelTest`、`SyncWorkerTest`、`AccountOperationLockTest`、`ReaderCacheAccountTest`、`GroupWithFeedsListUseCaseTest` |
| 清理、归档与数据库迁移 | `ArticleCleanupUseCase`、`ArticleDao`、`ArchivedArticle`、`DatabaseModule` | `ArticleCleanupDatabaseTest`、`DatabaseMigrationTest`、`WebDavBackupEngineTest` |
| 凭据与服务器安全 | `CredentialCipher`、`ServerSecurityStore`、`OkHttpClientModule`、系统备份规则 | `CredentialCipherTest`、`ServerSecurityTest`、`CredentialDeviceTest` |
| WebDAV 配对、冲突、备份与恢复 | `WebDavConfigStore`、`WebDavBackupEngine`、`WebDavSyncPage` / `WebDavSyncViewModel` | `WebDavConfigStoreTest`、`WebDavSnapshotValidationTest`、`WebDavBackupEngineTest`、`WebDavSyncViewModelTest` |
| 全文搜索与阅读状态可靠写回 | `ArticleSearchDao`、`ArticleSearchQuery`、`ArticleStateOutbox`、`PendingArticleStateDao` | `ArticleSearchDatabaseTest`、`ArticleStateOutboxTest` |
| 四种新增服务 | `RemoteReaderClient`、`RemoteRssService`、`RemoteAccountDialog` | `RemoteReaderClientTest`、`RemoteRssServiceTest`；真实账号联调尚未完成 |
| 通知 / Widget 导航与分页释放 | `LaunchTargetResolver`、`MainActivity`、`WidgetRepository`、`ArticlePagingListUseCase` | `LaunchTargetResolverTest`、`AccountNavigationDeviceTest`、`ArticlePagingListMemoryTest`、`NotificationMemoryTest` |

### 仍需说明的边界

- 新服务已完成模拟协议与真实 Room 回归，尚未使用用户的真实 Feedly / Inoreader / Miniflux / Tiny Tiny RSS 账号验证。API token 权限、套餐、服务端配置与旧文章标未读限制因服务而异；不能将 HTTP 成功等同于每个旧条目均已写回。
- 这四类新服务的订阅管理在服务端网页完成，应用内不开放新增、删除、移动订阅；本机阅读、收藏和保存供以后阅读可用。
- WebDAV 需要支持强 ETag 和条件 PUT 的服务器，无法安全覆盖时明确报错；v1 备份只读兼容。历史列表保留 20 个先前版本，尚不自动回收服务器上的不可达旧分块，服务器空间并非固定上限。
- “按记录时间选择”使用备份记录时间，依赖设备时钟，不能保证还原精确的离线编辑先后；冲突默认由用户选择。
- 600 篇大正文测试验证了分块、压缩、复用和恢复；百万篇规模与低磁盘空间仍需专门的性能 / 故障演练。
- 设备验证使用独立临时 AVD `readyou-validation`（API 34，`emulator-5580`），目录 `/private/tmp/readyou-validation-avd`。已有模拟器和用户应用数据不参与此验证。
- 仍需在最低支持的 API 26、较新 Android 版本及厂商硬件 Keystore 上做兼容性验收；此次真实进程重启测试只覆盖 API 34 模拟器。
