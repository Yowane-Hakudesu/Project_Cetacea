pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WhaleChan"
// :app      被操控端（无障碍 / 通知 / Agent）
// :protocol 两端共享的 JSON 协议与 WebSocket 客户端
// :companion 独立伴侣端 APK
include(":app", ":protocol", ":companion")
