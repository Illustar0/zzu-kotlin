import dev.detekt.gradle.Detekt
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.poko)
    alias(libs.plugins.detekt)
    alias(libs.plugins.maven.publish)
}

group = "com.illustar0"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()

/** 为单个 Apple 目标生成指向解压后 `libzzu.h` 与 `libzzu.a` 的 `cinterop` `.def` 文件。 */
abstract class GenerateLibzzuDef : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extractedDirectory: DirectoryProperty

    @get:OutputFile
    abstract val definitionFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val root = extractedDirectory.get().asFile
        val includeDir = root.resolve("include").absolutePath
        val libDir = root.resolve("lib").absolutePath
        val content = """
            package = com.illustar0.zzu.internal.cinterop
            headers = libzzu.h
            headerFilter = libzzu.h
            staticLibraries = libzzu.a
            libraryPaths = "$libDir"
            compilerOpts = "-I$includeDir"
            linkerOpts = -framework Security -framework CoreFoundation -liconv
        """.trimIndent() + "\n"
        val output = definitionFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(content)
    }
}

/** 校验 `libzzu` 发布包的 SHA-256，再按 [libraries] 把目标文件解压到 [outputDirectory]。 */
abstract class ExtractLibzzu @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    /** `libzzu-<target>.tar.gz` 发布包。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val packages: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val checksumFiles: ConfigurableFileCollection

    /** 发布包内的文件路径（如 `libzzu-aarch64-linux-android/lib/libzzu.so`）到输出路径的映射。 */
    @get:Input
    abstract val libraries: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val checksumFilesByName = checksumFiles.associateBy { it.name }
        packages.forEach { archive ->
            val checksumName = "${archive.name}.sha256"
            val checksumFile = checkNotNull(checksumFilesByName[checksumName]) { "缺少校验文件 $checksumName" }
            val expectedSha256 = checksumFile.readText().trimStart().takeWhile { !it.isWhitespace() }
            check(sha256(archive).equals(expectedSha256, ignoreCase = true)) {
                "${archive.name} 的 SHA-256 与 $checksumName 中的记录不符"
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

fun libzzuArtifacts(targets: Collection<String>, extension: String): Configuration {
    val libzzu = libs.libzzu.get()
    val coordinates = "${libzzu.group}:${libzzu.name}:${libzzu.version}"
    val artifacts = targets.map { target -> dependencies.create("$coordinates:$target@$extension") }
    return configurations.detachedConfiguration(*artifacts.toTypedArray())
}

kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    android {
        namespace = "com.illustar0.zzu"
        compileSdk = 37
        minSdk = 26
        aarMetadata {
            minCompileSdk = 26
        }
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val appleTargets = listOf(
        iosArm64() to "aarch64-apple-ios",
        iosSimulatorArm64() to "aarch64-apple-ios-sim",
        iosX64() to "x86_64-apple-ios",
        macosArm64() to "aarch64-apple-darwin",
        macosX64() to "x86_64-apple-darwin",
    )

    appleTargets.forEach { (kotlinTarget, rustTarget) ->
        val targetSuffix = kotlinTarget.name.replaceFirstChar(Char::uppercaseChar)
        val extractTask = tasks.register<ExtractLibzzu>("extract${targetSuffix}Libzzu") {
            group = "build"
            description = "Extracts static libzzu and headers for ${kotlinTarget.name}."

            packages.from(libzzuArtifacts(listOf(rustTarget), "tar.gz"))
            checksumFiles.from(libzzuArtifacts(listOf(rustTarget), "tar.gz.sha256"))
            libraries = mapOf(
                "libzzu-$rustTarget/include/libzzu.h" to "include/libzzu.h",
                "libzzu-$rustTarget/lib/libzzu.a" to "lib/libzzu.a",
            )
            outputDirectory = layout.buildDirectory.dir("generated/libzzu/${kotlinTarget.name}")
        }

        val generateDefTask = tasks.register<GenerateLibzzuDef>("generate${targetSuffix}LibzzuDef") {
            description = "Generates cinterop definition file for ${kotlinTarget.name}."

            extractedDirectory.set(extractTask.flatMap { it.outputDirectory })
            definitionFile.set(layout.buildDirectory.file("generated/cinterop/${kotlinTarget.name}/libzzu.def"))
        }

        val interop = kotlinTarget.compilations.getByName("main").cinterops.create("libzzu") {
            definitionFile.set(generateDefTask.flatMap { it.definitionFile })
        }

        tasks.named(interop.interopProcessingTaskName) {
            inputs.dir(extractTask.flatMap { it.outputDirectory })
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .withPropertyName("libzzuStatic")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val jnaMain = create("jnaMain") {
            dependsOn(commonMain.get())
            dependencies {
                compileOnly(libs.jna)
            }
        }
        androidMain {
            dependsOn(jnaMain)
            dependencies {
                implementation("${libs.jna.get()}@aar")
            }
        }
        jvmMain {
            dependsOn(jnaMain)
            dependencies {
                implementation(libs.jna)
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
}

/** 返回本机平台的 `libzzu` Rust 目标与动态库文件名；没有对应发布包时为 `null`。 */
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

/** Android ABI 到 `libzzu` 发布包 Rust 目标的映射。 */
val androidTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)

/** JNA 资源目录路径到 `libzzu` 发布包 Rust 目标及动态库文件名的映射。 */
val desktopTargets = mapOf(
    "darwin-aarch64/libzzu.dylib" to ("aarch64-apple-darwin" to "libzzu.dylib"),
    "darwin-x86-64/libzzu.dylib" to ("x86_64-apple-darwin" to "libzzu.dylib"),
    "win32-x86-64/zzu.dll" to ("x86_64-pc-windows-msvc" to "zzu.dll"),
    "win32-aarch64/zzu.dll" to ("aarch64-pc-windows-msvc" to "zzu.dll"),
    "linux-x86-64/libzzu.so" to ("x86_64-unknown-linux-gnu" to "libzzu.so"),
    "linux-aarch64/libzzu.so" to ("aarch64-unknown-linux-gnu" to "libzzu.so"),
)

val desktopLibrary = desktopLibzzu(
    providers.systemProperty("os.name").get(),
    providers.systemProperty("os.arch").get(),
)

val extractAndroidLibzzu = tasks.register<ExtractLibzzu>("extractAndroidLibzzu") {
    group = "build"
    description = "Extracts Android libzzu shared libraries for packaging into the AAR."

    packages.from(libzzuArtifacts(androidTargets.values, "tar.gz"))
    checksumFiles.from(libzzuArtifacts(androidTargets.values, "tar.gz.sha256"))
    libraries = androidTargets.entries.associate { (abi, target) ->
        "libzzu-$target/lib/libzzu.so" to "$abi/libzzu.so"
    }
    outputDirectory = layout.buildDirectory.dir("generated/jniLibs")
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(extractAndroidLibzzu, ExtractLibzzu::outputDirectory)
}

val extractAllDesktopLibzzu = tasks.register<ExtractLibzzu>("extractAllDesktopLibzzu") {
    group = "build"
    description = "Extracts desktop libzzu shared libraries for packaging into the JVM JAR."

    val rustTargets = desktopTargets.values.map { it.first }
    packages.from(libzzuArtifacts(rustTargets, "tar.gz"))
    checksumFiles.from(libzzuArtifacts(rustTargets, "tar.gz.sha256"))
    libraries = desktopTargets.entries.associate { (resourcePath, targetAndFile) ->
        val (target, library) = targetAndFile
        "libzzu-$target/lib/$library" to resourcePath
    }
    outputDirectory = layout.buildDirectory.dir("generated/jvmResources")
}

tasks.named<Jar>("jvmJar") {
    from(extractAllDesktopLibzzu)
}

// 本机 JVM 测试仅按需解压当前平台 1 个动态库；平台没有发布包时不设置库路径，原生集成测试自动跳过。
desktopLibrary?.let { (target, library) ->
    val extractHostDesktopLibzzu = tasks.register<ExtractLibzzu>("extractHostDesktopLibzzu") {
        description = "Extracts the host desktop libzzu shared library for local JVM tests."

        packages.from(libzzuArtifacts(listOf(target), "tar.gz"))
        checksumFiles.from(libzzuArtifacts(listOf(target), "tar.gz.sha256"))
        libraries = mapOf("libzzu-$target/lib/$library" to library)
        outputDirectory = layout.buildDirectory.dir("libzzu/desktop")
    }

    tasks.named<Test>("jvmTest") {
        val libraryDirectory = extractHostDesktopLibzzu.flatMap { it.outputDirectory }
        inputs.dir(libraryDirectory)
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("libzzuDesktop")
        jvmArgumentProviders.add(
            CommandLineArgumentProvider { listOf("-Djna.library.path=${libraryDirectory.get().asFile.path}") },
        )
    }
}

val hasIosSimulator = providers.exec {
    isIgnoreExitValue = true
    commandLine("xcrun", "simctl", "list", "devices", "available")
}.standardOutput.asText.map { output -> "iPhone" in output }

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    onlyIf("本机已安装可用的 iOS Simulator 设备") { hasIosSimulator.get() }
}

// 挂载全部源集扫描任务以及 JVM / Android 类型解析检查任务（如 `detektMainJvm`、`detektTestJvm`、`detektMainAndroid`）。
tasks.named("check") {
    dependsOn(tasks.withType<Detekt>())
}

mavenPublishing {
    coordinates(artifactId = "zzu")
    publishToMavenCentral()
    signAllPublications()

    val repository = providers.environmentVariable("GITHUB_REPOSITORY").orElse("Illustar0/zzu-kotlin").get()
    val projectUrl = "https://github.com/$repository"
    pom {
        name.set("zzu-kotlin")
        description.set("Kotlin Multiplatform SDK for Zhengzhou University services")
        url.set(projectUrl)
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
            }
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("Illustar0")
                name.set("Illustar0")
            }
        }
        scm {
            url.set(projectUrl)
            connection.set("scm:git:$projectUrl.git")
            developerConnection.set("scm:git:ssh://git@github.com/$repository.git")
        }
    }
}
