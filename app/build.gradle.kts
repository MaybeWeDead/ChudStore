// app/build.gradle.kts
//
// Зависимости сведены к минимуму осознанно (см. обсуждение в чате —
// сборка идёт на устройстве через AndroidIDE, каждая лишняя библиотека
// это время Gradle sync + heap на dexing, которых на планшете мало):
//
//   - Compose BOM + минимальный набор (foundation, material3, ui-tooling
//     ТОЛЬКО в debug) — никакого accompanist, никакого navigation-compose
//     (у нас 2 экрана, ручной when-based switch дешевле, чем тянуть весь
//     Navigation-Compose граф ради двух состояний)
//   - Shizuku api + provider — ядро всего проекта, без вариантов
//   - HiddenApiBypass (LSPosed) — обязателен для вызова IPackageManager/
//     IPackageInstaller AIDL Stub классов на Android 9+ (см. обсуждение:
//     hidden API greylist/blacklist блокирует прямой доступ без обхода)
//
// Осознанно НЕ добавлено: Coil (иконки приложений берём через
// PackageManager#getApplicationIcon() — уже Drawable, локально, без
// сети — Coil тут просто мёртвый вес), Room (список установленных
// приложений не нужно кэшировать в БД, PackageManager и так быстрый
// in-memory источник правды), Hilt/Dagger (весь граф зависимостей —
// три синглтона, ручной DI через object проще и не требует annotation
// processing, который на слабом железе особенно чувствителен по времени).

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maybewedead.chudstore"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maybewedead.chudstore"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Kotlin 1.9.24 использует старый способ подключения
    // Compose Compiler. Новый plugin
    // org.jetbrains.kotlin.plugin.compose предназначен для Kotlin 2.0+.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.core:core-ktx:1.13.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
