# zzu-kotlin

郑州大学校园服务的 Kotlin Multiplatform 客户端 SDK，基于 [libzzu](https://github.com/Illustar0/libzzu) 构建，涵盖统一身份认证、本科教务、校园卡、宿舍电控、校园邮箱与校园网。

SDK 基于 Kotlin 协程、`kotlinx.serialization` 与 `kotlinx-datetime` 实现，公共业务代码与精确十进制类型 `Decimal` 位于 [`src/commonMain/kotlin/com/illustar0/zzu`](src/commonMain/kotlin/com/illustar0/zzu)。各平台目标均内置对应的 `libzzu` 原生库，下游接入无需额外配置动态库或静态库路径。

## 支持平台

| 平台 | Gradle 目标 | 原生桥接与分发方式 |
| :--- | :--- | :--- |
| Android | `android`（`minSdk = 26`） | JNA，AAR 内置 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` 动态库 |
| JVM | `jvm`（Java 17+） | JNA，JAR 内置 macOS、Linux GNU 与 Windows MSVC 的 `x86_64`、`aarch64` 动态库 |
| iOS | `iosArm64`、`iosSimulatorArm64`、`iosX64` | Kotlin/Native `cinterop`，`.klib` 内嵌 `libzzu.a` 静态库 |
| macOS | `macosArm64`、`macosX64` | Kotlin/Native `cinterop`，`.klib` 内嵌 `libzzu.a` 静态库 |

在 JVM 与 Android 平台可通过 `Decimal.toBigDecimal()` 和 `BigDecimal.toDecimal()` 与 `java.math.BigDecimal` 互转；在 Apple 平台可通过 `Decimal.toNSDecimalNumber()` 转换为 `NSDecimalNumber`。

## 构建与校验

[`build.gradle.kts`](build.gradle.kts) 按 [`gradle/libs.versions.toml`](gradle/libs.versions.toml) 指定的版本，从 `libzzu` GitHub Release 下载原生库压缩包与 `.tar.gz.sha256` 校验文件，校验 SHA-256 通过后解压打包。Apple 目标的 `cinterop` 绑定依赖 Xcode 工具链，完整构建需在 macOS 上执行：

```sh
# 运行静态检查（Detekt、Android Lint）与多平台测试
./gradlew check

# 构建 Android AAR 与内置全桌面动态库的 JVM JAR
./gradlew assembleAndroidMain jvmJar
```

`jvmTest` 仅在受支持的桌面架构上加载本机动态库执行集成测试，其余架构自动跳过。macOS 主机还会直接链接 `libzzu.a` 执行原生测试；若本机装有可用的 iOS Simulator 设备，模拟器测试也会一并运行。

## 许可

MIT OR Apache-2.0
