// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // AGP: Giữ 8.13.0
    id("com.android.application") version "8.13.0" apply false

    // Kotlin: Nâng lên 1.9.25
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false

    // KSP: Phiên bản khớp với Kotlin (1.9.25-1.0.20)
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false

    // THÊM KHAI BÁO SAFE ARGS
    id("androidx.navigation.safeargs.kotlin") version "2.8.3" apply false
}