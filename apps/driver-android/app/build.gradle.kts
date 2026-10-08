plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.snapnest.dispatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snapnest.dispatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
}
