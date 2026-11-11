# 通知入口 OOM 排查记录

## 报告与验证范围

- 用户环境：Read You 0.16.2，Samsung SM-S9110，Android 16 / API 36，Java 堆上限 256 MiB。
- 用户操作：应用内积累超过一万篇未读文章，从系统通知打开后白屏并发生 OOM。
- 原始堆栈中的 `BufferedChannel` 是内存分配失败的位置，不能单凭这一帧确定内存持有者。
- 2026-11-12 连接到的 ADB 目标为 Android 13 / SM-G998B，未安装 Read You，随后连接断开。
- 本次复现使用独立的 Android 14 / API 34 ARM64 模拟器，实际 Java 堆上限为 192 MiB。
- 没有获得用户原始数据库、崩溃前日志或堆转储。因此，本修复针对已复现的分页缓存泄漏；尚不能确认原始 Android 16 崩溃的完整触发链。

## 已复现的泄漏

`ArticlePagingListUseCase` 每次收到筛选或账号变更，都会创建一个新的 `Pager`，并对它调用 `cachedIn(applicationScope)`。展示器的 `collectLatest` 会取消旧的收集，但旧缓存的后台任务仍属于应用作用域，继续持有加载过的页面及文章 HTML。

回归测试使用真实 Room 数据库和生产环境的用例类，插入 200 篇带正文的文章，在“全部订阅源未读”和“单个订阅源未读”之间切换 10 次。每次都等待新一代页面实际加载，以避免 StateFlow 合并连续操作。用弱引用检查旧文章能否回收，同时统计注入该用例的测试作用域的直接子任务。

| 相同测试 | 修复前 | 修复后 |
| --- | --- | --- |
| 初始 / 最终子任务数 | 4 / 24 | 2 / 2 |
| GC 后旧文章是否仍被持有 | 是 | 否 |
| 测试末尾 Java 堆占用 | 72,800,328 字节，约 69 MiB | 10,520,064 字节，约 10 MiB |

修复前连续两次得到相同的任务增长和旧文章保留结果。堆占用仅用于辅助观察，测试不以易受运行时影响的绝对内存数字作为通过条件。

将同一测试的切换次数提高到 50 后，修复前确实发生 OOM：

```text
java.lang.OutOfMemoryError: Failed to allocate a 40040 byte allocation
with 29120 free bytes and 28KB until OOM,
target footprint 201326592, growth limit 201326592
at android.database.CursorWindow.nativeGetString(Native Method)
```

原版在完成 50 次之前耗尽内存，崩溃报告界面又因堆空间不足发生二次 OOM；测试在 45 秒超时后被停止。修复版完成相同的 50 次切换，用时 23.696 秒，子任务数保持 2 / 2，旧文章已释放，Java 堆为 10,583,232 字节，测试通过。这里复现的是筛选切换造成的 OOM，分配失败位置与用户报告的协程栈不同。

## 修复

让每一组账号和筛选条件拥有自己的协程作用域，并在该作用域调用 `cachedIn`。外层使用 `collectLatest`，切换条件时先取消上一组缓存，再发布新的 `PagerData`。`awaitCancellation()` 使当前缓存一直存活到条件切换或应用作用域取消。

保留共享缓存，使列表界面和用例内的 `PagingDataPresenter` 仍可消费同一代分页数据。回归测试固定检查旧正文回收和任务数稳定，不通过删除历史文章来降低内存。

## 通知入口测试

`NotificationMemoryTest` 创建独立本地账号，分批插入 10,050 篇带 HTML 正文的未读文章，再调用生产环境的 `NotificationHelper` 发布一篇文章的通知。测试发送该通知的实际 `PendingIntent`，并检查读者 WebView 的正文标记，能够识别进入应用后正文一直不显示的情况。

发送时临时采用 shell 的后台启动权限，以模拟 System UI 点击通知时拥有的启动权限；否则 Android 14 会在进入应用前拦截测试。测试结束后关闭测试页面，删除测试账号及其数据，并恢复原账号和相关显示设置。

Review 时修正了两项设备测试的账号清理顺序：先删除测试账号，再恢复原账号。`AccountService.delete()` 会自动选择剩余列表的第一个账号，若先恢复再删除，就会覆盖原账号选择。

分页缓存修复前后的首轮测试均能显示正文，说明“库内超过一万条未读”本身在该环境下不足以重现原始 OOM。它作为通知入口的冒烟测试，不能替代原设备验证；后续回归也检测出了下面的启动竞争。

## 通知启动时的初始化竞争

最后一轮通知测试发现另一个独立的启动问题：后台协程抛出以下异常，崩溃页覆盖读者，正文显示检查因而超时：

```text
java.lang.NullPointerException:
Attempt to invoke interface method 'java.lang.Object kotlinx.coroutines.flow.Flow.collect(...)'
on a null object reference
at kotlinx.coroutines.flow.internal.CombineKt$combineInternal$2$1.invokeSuspend(Combine.kt:28)
```

`GroupWithFeedsListUseCase` 在 `init` 中启动后台协程，但 `feedsFlow` 和输出状态流声明在 `init` 之后。协程可能在构造函数执行完之前运行，读到尚未初始化的字段。

新增 `GroupWithFeedsListUseCaseTest`：给依赖提供有效且立即发出值的数据流，使用 `Dispatchers.Unconfined` 使收集在构造期间开始。修改前测试稳定失败，既捕获到 `feedsFlow.setValue` 空指针，也捕获到与设备日志相同的 `Flow.collect` 空指针。修复将状态流初始化移到所有协程启动之前。

该空指针与用户提供的 OOM 不是同一个异常；它是此次通知端到端回归中发现并一并处理的问题。

## 运行

准备已完成启动的测试模拟器，并将 Android SDK 配置到 `ANDROID_HOME`。以下示例以本次模拟器序列号 `emulator-5580` 为例：

```sh
./gradlew assembleGithubDebug assembleGithubDebugAndroidTest testGithubDebugUnitTest
```

从 `app/build/outputs/apk/github/debug/output-metadata.json` 和 `app/build/outputs/apk/androidTest/github/debug/output-metadata.json` 获取 APK 文件名，将应用和测试 APK 安装到测试目标后运行：

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e class me.ash.reader.ArticlePagingListMemoryTest,me.ash.reader.NotificationMemoryTest \
  me.ash.reader.test/androidx.test.runner.AndroidJUnitRunner
```

单独验证泄漏时，测试只需数秒；可以用 `-e filterChanges 50` 提高筛选切换次数。测试使用真实应用数据库，请在测试安装中运行。

两处修复合入本地工作区后，11 个 JVM 单元测试全部通过；2 个设备测试连续两轮通过，分别用时 17.652 和 17.989 秒。50 次筛选切换的压力测试也已通过。`git diff --check` 通过。

Review 后再次运行设备测试，首次在模拟器刚启动时出现分页等待超时和 UiAutomation 连接断开；系统稳定后重跑，同一组 2 个测试全部通过，用时 21.281 秒。对应输出保存在 `build/oom-review-device-tests-2.txt`。

调试 APK：`build/ReadYou-0.16.2-oom-fix-debug.apk`。测试输出保存在本地忽略的 `build/` 目录中。原始 Android 16 真机上的通知点击和内存变化仍待验证。
