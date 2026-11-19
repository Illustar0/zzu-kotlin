# zzu-kotlin

[libzzu](https://github.com/Illustar0/libzzu) 的 Android Kotlin SDK，基于 JNA、协程与 kotlinx.serialization。

## 接入

SDK 无需初始化。接入方的 `compileSdk` 至少为 26，SDK 的 `minSdk` 为 26。

TLS 证书用系统内置的根证书校验，不信任用户安装的证书，也不遵循应用的 `network_security_config`。

## 用法

五个客户端彼此独立，都是 `AutoCloseable`。`EasClient` 与 `EcardClient` 从 `CasClient` 派生并共享其会话：

```kotlin
val cas = CasClient("学号", "密码") { timeout = 10.seconds }
cas.login { promptSmsCode() } // 只在需要多因素认证时调用
val mailUrl = cas.serviceLoginUrl(SsoService.EMAIL) // 另有 LIBRARY、LIBRARY_RESERVATION、LEAVE
val presidentMailboxUrl = cas.serviceLoginUrl(SsoService.PRESIDENT_MAILBOX)
val ecardUrl = cas.serviceLoginUrl(SsoService.ECARD)
val gradesUrl = cas.serviceLoginUrl(SsoService.UndergradEas.GRADES)

val eas = EasClient(cas).apply { login() }
val week = eas.teachingWeek(week = 3)
val lesson = week[DayOfWeek.MONDAY, 1]
val calendar = eas.teachingWeeks().toICalendar()

val ecard = EcardClient(cas).apply { login() }
val balance = ecard.balance()

PortalClient(discoverPortal()).use { portal -> portal.login("学号", "密码") }
PortalClient(discoverPortal(), PortalSourceAddress.SYSTEM).use { it.login("学号", "密码") } // 位于 NAT 路由器后时
SelfServiceClient("http://自助服务地址").use { it.login("学号", "密码") }
```

挂起函数都可在主线程调用，失败时抛 `ZzuException`。SDK 不会在令牌失效时隐式重登，令牌持久化由应用负责，见 `CasClient` 的文档。
`SsoService.UndergradEas` 支持 10 个本科教务子应用，包括学籍、培养方案、考试、学业预警、成绩、课表、全校开课、校历、空闲教室与教室课表。
校长信箱、校园卡与本科教务子应用的登录地址由 libzzu 在本地生成，不发起网络请求，前两者使用 `token` 参数，后者使用 `idToken`。返回的 URL 携带用户令牌，切勿写入日志。
`rechargeEnergy` 被取消、超时或抛出 `ZzuException.Decode`、`ZzuException.Crypto` 后服务端可能已受理，须先查询余额再决定是否重试。

## 开发

原生库从 libzzu 的 GitHub Release 下载，版本由 `gradle/libs.versions.toml` 的 `libzzu` 决定。下载的发布包须与 `gradle/libzzu.sha256` 中固定的 SHA-256 一致，升级版本后重新生成该文件：

```bash
gh release view v0.1.0 -R Illustar0/libzzu --json assets --jq '.assets[] | select(.name | endswith(".tar.gz")) | "\(.digest | ltrimstr("sha256:"))  \(.name)"' > gradle/libzzu.sha256
```

```bash
./gradlew testDebugUnitTest
```

本机测试加载本机平台的桌面版发布包（macOS、Linux GNU 与 Windows MSVC 的 x86_64 和 aarch64），其他平台跳过原生集成测试。

静态检查用 detekt，规则调整写在 `config/detekt/detekt.yml`，`check` 已包含这两个任务：

```bash
./gradlew detektMain detektTest
```
