plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.navigation.safeargs.kotlin")
}

android {
    namespace = "com.example.realtalkenglishwithAI"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.realtalkenglishwithAI"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Đảm bảo sử dụng JDK 17
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Kích hoạt Jetpack Compose
    buildFeatures {
        viewBinding = true // Giữ lại cho các màn hình cũ
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15" // Tương thích với Kotlin 1.9.25
    }

    aaptOptions {
        noCompress(".zip") // Sửa lại để không nén các file có đuôi .zip
    }
}

dependencies {
    // --- CÁC THƯ VIỆN COMPOSE MỚI ---
    // BOM quản lý tất cả phiên bản thư viện Compose
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    // Core & UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Tích hợp với ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    // Thư viện icon của Material Design
    implementation("androidx.compose.material:material-icons-extended")


    // --- CÁC THƯ VIỆN HIỆN CÓ CỦA BẠN ---
    // Core & UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.3")

    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Networking with Retrofit and Gson
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // Image Loading
    implementation("io.coil-kt:coil:2.6.0")

    // Media Playback with ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")

    // Vosk Speech Recognition (from local .aar file)
    implementation(files("libs/vosk-android-0.3.70.aar"))
    // Add JNA dependency required by Vosk
    implementation("net.java.dev.jna:jna:5.18.0@aar")

    // Phonetic analysis for Beginner mode
    implementation("commons-codec:commons-codec:1.17.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}