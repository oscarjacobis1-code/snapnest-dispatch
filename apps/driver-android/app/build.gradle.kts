plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 4
        versionName = "0.3.1"
        buildConfigField("String", "DISPATCH_API_URL", "\"${dispatchApiUrl.get().trimEnd('/')}\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation("io.livekit:livekit-android:2.29.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
