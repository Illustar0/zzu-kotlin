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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

/** 把 libzzu 打包产物中的 `libzzu.so` 按 ABI 目录收集到 AAR。 */
abstract class CollectNativeLibraries @Inject constructor(
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:Internal
    abstract val packages: DirectoryProperty

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val targets: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val libraries: FileCollection
        get() = packages.get().asFileTree.matching { include(libraryPaths()) }

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun collect() {
        check(!libraries.isEmpty) {
            "未在 ${packages.get()} 找到 Android 版 libzzu ${version.get()}，" +
                "请先在 libzzu 中运行 `dotnet run --project build -- Package --platform <target>`"
        }
        files.sync {
            into(outputDirectory)
            targets.get().forEach { (abi, target) ->
                from(packages.dir("${packageName(target)}/lib")) {
                    include("libzzu.so")
                    into(abi)
                }
            }
        }
    }

    private fun libraryPaths(): List<String> =
        targets.get().values.map { "${packageName(it)}/lib/libzzu.so" }

    private fun packageName(target: String): String = "libzzu-${version.get()}-$target"
}

val libzzuDirectory = layout.projectDirectory.dir(providers.gradleProperty("libzzu.dir").get())

val collectNativeLibraries = tasks.register<CollectNativeLibraries>("collectNativeLibraries") {
    group = "build"
    description = "Collects Android libzzu shared libraries for packaging into the AAR."

    packages = libzzuDirectory.dir("dist")
    version = providers.gradleProperty("libzzu.version")
    targets = mapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "armv7-linux-androideabi",
        "x86_64" to "x86_64-linux-android",
        "x86" to "i686-linux-android",
    )
    outputDirectory = layout.buildDirectory.dir("generated/jniLibs")
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(
        collectNativeLibraries,
        CollectNativeLibraries::outputDirectory,
    )
}

// 本机 JVM 测试直接加载 libzzu 的桌面版动态库。
tasks.withType<Test>().configureEach {
    systemProperty("jna.library.path", libzzuDirectory.dir("target/release").asFile.path)
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
