plugins {
    id("com.android.application")
}

android {
    namespace = "com.addblocker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.addblocker.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
