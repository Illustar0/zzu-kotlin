# zzu-kotlin

对郑州大学相关系统的 Android Kotlin 客户端封装，基于 [libzzu](https://github.com/Illustar0/libzzu)，包含统一身份认证、本科教务、校园卡、宿舍电控、校园邮箱与校园网。

SDK 基于 JNA、Kotlin 协程与 `kotlinx.serialization` 构建，各客户端与数据模型位于 [`src/main/kotlin/com/illustar0/zzu`](src/main/kotlin/com/illustar0/zzu)，最低支持 Android API 26（`minSdk = 26`）。构建脚本 [`build.gradle.kts`](build.gradle.kts) 会按 [`gradle/libs.versions.toml`](gradle/libs.versions.toml) 指定的版本从 `libzzu` GitHub Release 下载原生库压缩包和对应的 `.tar.gz.sha256` 附件，校验通过后解压并将 Android 动态库打包进 AAR。

## 构建

构建脚本基于 Gradle：

```sh
# 运行静态检查（Detekt、Android Lint）与单元测试
./gradlew check

# 构建 Release AAR
./gradlew assembleRelease
```

本机单元测试会加载对应平台的桌面版 `libzzu`（支持 macOS、Linux GNU 与 Windows MSVC 的 `x86_64`、`aarch64`），其他平台自动跳过原生集成测试。

## 许可

MIT OR Apache-2.0
