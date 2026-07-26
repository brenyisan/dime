plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dime.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dime.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // configura signingConfig si firmas la APK
        }
        getByName("debug") {
            isDebuggable = true
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Ajusta la versión del compiler si necesitas otra versión compatible con Kotlin/Compose
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Si la librería FFmpeg incluye muchos archivos nativos, puedes necesitar ajustar packagingOptions.
    // packagingOptions {
    //     resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt")
    // }
}

dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // FFmpeg (Full GPL artifact requested)
    implementation("com.antonkarpenko:ffmpeg-kit-full-gpl:2.2.1")

    // DocumentFile helper
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Coroutines (Android)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
