plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.duet.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.duet.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    // Jetpack WindowManager: reports the Surface Duo hinge as a FoldingFeature.
    implementation("androidx.window:window:1.3.0")
}
