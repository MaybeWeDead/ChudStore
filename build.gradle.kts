// build.gradle.kts (project root)
//
// Минимальный набор плагинов — только то, что реально нужно: AGP +
// Kotlin + Compose compiler plugin. Никаких дополнительных build-tools
// плагинов (детектор багов, линт-расширения и т.д.) — каждый лишний
// плагин это дополнительное время classpath resolution при Gradle sync,
// а сборка идёт на устройстве через AndroidIDE, где это ощутимо.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "1.9.24" apply false
}
