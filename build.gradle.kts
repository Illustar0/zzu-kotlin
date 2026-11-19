import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.poko)
    alias(libs.plugins.detekt)
    `maven-publish`
}

group = "dev.zzu"
version = "0.1.0"

android {
    namespace = "dev.zzu"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        aarMetadata {
            minCompileSdk = 26
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    explicitApi()
}

detekt {
    buildUponDefaultConfig = true
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)

    implementation("${libs.jna.get()}@aar")

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.jna)
}

/** 校验 libzzu 发布包的 SHA-256，再按 [libraries] 把其中的动态库解压到 [outputDirectory]。 */
abstract class ExtractLibzzu @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    /** `libzzu-<target>.tar.gz` 发布包。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packages: ConfigurableFileCollection

    /** `sha256sum` 格式的可信摘要清单。 */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val checksums: RegularFileProperty

    /** 发布包内的库路径（如 `libzzu-aarch64-linux-android/lib/libzzu.so`）到输出路径的映射。 */
    @get:Input
    abstract val libraries: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val trustedDigests = checksums.get().asFile.readLines().map { it.substringBefore(' ') }.toSet()
        packages.forEach { archive ->
            check(sha256(archive) in trustedDigests) {
                "${archive.name} 的 SHA-256 未列在 ${checksums.get()} 中；升级 libzzu 后须重新生成该文件"
            }
        }
        val outputPaths = libraries.get()
        files.sync {
            into(outputDirectory)
            packages.forEach { from(archives.tarTree(it)) }
            include(outputPaths.keys)
            eachFile { path = outputPaths.getValue(path) }
            includeEmptyDirs = false
        }
        val missing = outputPaths.values.filterNot { outputDirectory.file(it).get().asFile.isFile }
        check(missing.isEmpty()) { "libzzu 发布包中缺少 $missing" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        return HexFormat.of().formatHex(digest.digest())
    }
}

/** 返回本机平台的 libzzu Rust 目标与动态库文件名；没有对应发布包时为 `null`。 */
fun desktopLibzzu(osName: String, osArch: String): Pair<String, String>? {
    val arch = when (osArch) {
        "aarch64", "arm64" -> "aarch64"
        "amd64", "x86_64" -> "x86_64"
        else -> return null
    }
    return when {
        osName.startsWith("Mac") -> "$arch-apple-darwin" to "libzzu.dylib"
        osName.startsWith("Linux") -> "$arch-unknown-linux-gnu" to "libzzu.so"
        osName.startsWith("Windows") -> "$arch-pc-windows-msvc" to "zzu.dll"
        else -> null
    }
}

/** Android ABI 到 libzzu 发布包 Rust 目标的映射。 */
val androidTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)
val desktopLibrary = desktopLibzzu(
    providers.systemProperty("os.name").get(),
    providers.systemProperty("os.arch").get(),
)
val libzzuChecksums = layout.projectDirectory.file("gradle/libzzu.sha256")

val libzzuAndroid = configurations.dependencyScope("libzzuAndroid").get()
val libzzuDesktop = configurations.dependencyScope("libzzuDesktop").get()
val libzzuAndroidPackages = configurations.resolvable("libzzuAndroidPackages") { extendsFrom(libzzuAndroid) }
val libzzuDesktopPackages = configurations.resolvable("libzzuDesktopPackages") { extendsFrom(libzzuDesktop) }

dependencies {
    fun libzzuPackage(target: String) = variantOf(libs.libzzu) {
        classifier(target)
        artifactType("tar.gz")
    }
    androidTargets.values.forEach { libzzuAndroid(libzzuPackage(it)) }
    desktopLibrary?.let { (target, _) -> libzzuDesktop(libzzuPackage(target)) }
}

val extractAndroidLibzzu = tasks.register<ExtractLibzzu>("extractAndroidLibzzu") {
    group = "build"
    description = "Extracts Android libzzu shared libraries for packaging into the AAR."

    packages.from(libzzuAndroidPackages)
    checksums = libzzuChecksums
    libraries = androidTargets.entries.associate { (abi, target) ->
        "libzzu-$target/lib/libzzu.so" to "$abi/libzzu.so"
    }
    outputDirectory = layout.buildDirectory.dir("generated/jniLibs")
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(extractAndroidLibzzu, ExtractLibzzu::outputDirectory)
}

// 本机 JVM 测试加载桌面版 libzzu；本机平台没有发布包时不设置库路径，原生集成测试随之跳过。
desktopLibrary?.let { (target, library) ->
    val extractDesktopLibzzu = tasks.register<ExtractLibzzu>("extractDesktopLibzzu") {
        description = "Extracts the desktop libzzu shared library for local JVM tests."

        packages.from(libzzuDesktopPackages)
        checksums = libzzuChecksums
        libraries = mapOf("libzzu-$target/lib/$library" to library)
        outputDirectory = layout.buildDirectory.dir("libzzu/desktop")
    }

    tasks.withType<Test>().configureEach {
        val libraryDirectory = extractDesktopLibzzu.flatMap { it.outputDirectory }
        inputs.dir(libraryDirectory)
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("libzzuDesktop")
        jvmArgumentProviders.add(
            CommandLineArgumentProvider { listOf("-Djna.library.path=${libraryDirectory.get().asFile.path}") },
        )
    }
}

// `check` 默认只挂不做类型解析的 `detekt`，依赖类型解析的规则要靠这两个任务才会执行。
tasks.named("check") {
    dependsOn("detektMain", "detektTest")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "zzu"
            afterEvaluate { from(components["release"]) }
        }
    }
}
