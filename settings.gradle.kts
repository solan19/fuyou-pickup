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
        // ★★ vc706：**官方源优先、国内镜像兜底**。
        //   2026-11-09 连续踩了两个坑：
        //   ① 镜像在前时 aliyun 偶发 502（core-ktx / webkit 解析直接失败）；
        //   ② 只留官方时，kotlin-compiler-embeddable 会从 Maven Central 跳到 GitHub Release，
        //      那里偶发 504（连着三次失败）。
        //   ⇒ 顺序改成 官方 → 镜像：正常走官方，官方 5xx 时 Gradle 会继续试后面的镜像。
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://jitpack.io")
    }
}

rootProject.name = "IdCode"
include(":app")
