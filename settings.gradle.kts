// settings.gradle.kts
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
        mavenCentral() // dev.rikka.shizuku:api/provider опубликованы здесь напрямую, JitPack не нужен
    }
}

rootProject.name = "ChudStore"
include(":app")
