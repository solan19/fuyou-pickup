pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // 国内镜像优先：拉 androidx.webkit 等新依赖时比直连快得多
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        // ★ Stellar-API 走 JitPack（vc264b，同志要求加 Stellar 适配）
        maven("https://jitpack.io")
        google()
        mavenCentral()
    }
}

rootProject.name = "IdCode"
include(":app")
