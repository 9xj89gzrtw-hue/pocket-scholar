plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.pocketscholar"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.pocketscholar"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        // keep the native CLI extractable so it can be executed at runtime
        jniLibs { useLegacyPackaging = true }
    }
}
