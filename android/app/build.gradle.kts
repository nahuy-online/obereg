plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.obereg.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.obereg.player"
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

    // Нативные библиотеки ядра: libobereg_jni.so по ABI.
    // Собираются скриптом scripts/build-android.sh или скачиваются из CI-артефакта jniLibs.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Плеер: Media3 (ExoPlayer) + сетевой слой OkHttp, чтобы направлять воспроизведение
    // через локальный SOCKS5-прокси ядра (OkHttp умеет SOCKS из коробки).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebView: настройка прокси, чтобы видео YouTube могло идти через встроенный туннель.
    implementation("androidx.webkit:webkit:1.11.0")
}
