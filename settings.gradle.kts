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
        // ★ vc703：国内镜像**只在本地构建时**用 —— GitHub Actions runner 直连
        //   google()/mavenCentral() 又快又稳，而 aliyun 镜像偶发 502 Bad Gateway
        //   会让整个构建挂在依赖解析上（2026-11-09 连续两次：webkit 1.12.1、core-ktx 1.15.0）。
        //   CI 环境里有 CI=true，据此跳过镜像、直接走官方源。
        if (System.getenv("CI") == null) {
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/google")
        }
        // ★ Stellar-API 走 JitPack（vc264b，同志要求加 Stellar 适配）
        maven("https://jitpack.io")
        google()
        mavenCentral()
    }
}

rootProject.name = "IdCode"
include(":app")
