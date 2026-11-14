plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scorepractice.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.scorepractice.app"
        minSdk = 26
        targetSdk = 34
        // every GitHub build gets a higher number, so a new APK installs as an update
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.0"
    }
    // one fixed signing key for every build: Android only updates an app signed with the same key
    signingConfigs {
        create("fixed") {
            storeFile = file("oseon.jks")
            storePassword = "WBrPxvtY4a6pdffH8RO-ZgMg"
            keyAlias = "oseon"
            keyPassword = "WBrPxvtY4a6pdffH8RO-ZgMg"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("fixed") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
