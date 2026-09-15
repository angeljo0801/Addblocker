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
        versionCode = 4
        versionName = "1.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
