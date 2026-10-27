plugins {
    id("com.android.library")
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
        lint.targetSdk = AndroidConfig.targetSdk
    }

    buildTypes {
        release {
            isShrinkResources = false
            isMinifyEnabled = false
            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "me.timschneeberger.hiddenapi_impl"
}

dependencies {
    implementation("dev.rikka.shizuku:api:${AndroidConfig.shizukuVersion}")
    compileOnly(project(":hidden-api-stubs"))
}