# zzu-kotlin

[libzzu](../../RustOver/libzzu) 的 Android Kotlin SDK，基于 JNA、协程与 kotlinx.serialization。

## 接入

SDK 无需初始化，`minSdk` 为 26。TLS 证书用系统内置的根证书校验，不信任用户安装的证书，也不遵循应用的 `network_security_config`。

## 用法

五个客户端彼此独立，都是 `AutoCloseable`。`EasClient` 与 `EcardClient` 从 `CasClient` 派生并共享其会话：

```kotlin
val cas = CasClient("学号", "密码") { timeout = 10.seconds }
cas.login { promptSmsCode() } // 只在需要多因素认证时调用
val mailUrl = cas.serviceLoginUrl(SsoService.EMAIL) // 另有 LIBRARY、LIBRARY_RESERVATION、LEAVE
val presidentMailboxUrl = cas.serviceLoginUrl(SsoService.PRESIDENT_MAILBOX)
val gradesUrl = cas.serviceLoginUrl(SsoService.UndergradEas.GRADES)

val eas = EasClient(cas).apply { login() }
val week = eas.teachingWeek(week = 3)
val lesson = week[DayOfWeek.MONDAY, 1]
val calendar = eas.teachingWeeks().toICalendar()

val ecard = EcardClient(cas).apply { login() }
val balance = ecard.balance()

PortalClient(discoverPortal()).use { portal -> portal.login("学号", "密码") }
SelfServiceClient("http://自助服务地址").use { it.login("学号", "密码") }
```

挂起函数都可在主线程调用，失败时抛 `ZzuException`。SDK 不会在令牌失效时隐式重登，令牌持久化由应用负责，见 `CasClient` 的文档。
`SsoService.UndergradEas` 支持 11 个本科教务子应用，包括学籍、培养方案、选课、考试、学业预警、成绩、课表、全校开课、校历、空闲教室与教室课表。
校长信箱与本科教务子应用的登录地址由 libzzu 在本地生成，不发起网络请求，分别使用 `token` 与 `idToken` 参数。返回的 URL 携带用户令牌，切勿写入日志。
`rechargeEnergy` 被取消或超时后服务端可能已受理，须先查询余额再决定是否重试。

## 开发

原生库取自 libzzu 的打包产物，位置由 `gradle.properties` 的 `libzzu.dir` 与 `libzzu.version` 决定：

```bash
dotnet run --project build -- Package --platform aarch64-linux-android
```

```bash
./gradlew testDebugUnitTest
```

本机测试加载 `<libzzu.dir>/target/release` 下的桌面版动态库，缺失时跳过原生集成测试。

静态检查用 detekt，规则调整写在 `config/detekt/detekt.yml`，`check` 已包含这两个任务：

```bash
./gradlew detektMain detektTest
```
