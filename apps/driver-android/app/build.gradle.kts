plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val dispatchApiUrl = providers.gradleProperty("DISPATCH_API_URL")
    .orElse("https://snapnest-dispatch.onrender.com")

android {
    namespace = "com.snapnest.dispatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snapnest.dispatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.10.0"
        buildConfigField("String", "DISPATCH_API_URL", "\"${dispatchApiUrl.get().trimEnd('/')}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.livekit:livekit-android:2.29.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
