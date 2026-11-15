pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // libzzu 的发布包只是 GitHub Release 附件，没有元数据，按文件名直接解析；完整性由 gradle/libzzu.sha256 保证。
        exclusiveContent {
            forRepository {
                ivy("https://github.com/Illustar0/libzzu/releases/download") {
                    name = "libzzuReleases"
                    patternLayout { artifact("v[revision]/[artifact]-[classifier].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("io.github.illustar0", "libzzu") }
        }
    }
}

rootProject.name = "zzu-kotlin"
