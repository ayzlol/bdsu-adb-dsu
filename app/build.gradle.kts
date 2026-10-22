plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bdsu.app"
    compileSdk = 34
    signingConfigs {
        create("release") {
            val storeFile = file("bdsu-release.keystore")
            val storePassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getProperty("keystore.password") ?: ""
            val keyAlias = "bdsu"
            val keyPassword = System.getenv("KEY_PASSWORD") ?: System.getProperty("key.password") ?: ""
            if (storeFile.exists() && storePassword.isNotBlank() && keyPassword.isNotBlank()) {
                this.storeFile = storeFile
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }
    defaultConfig {
        applicationId = "com.ayzlol.bdsu"
        minSdk = 29
        targetSdk = 34
        versionCode = 114
        versionName = "1.3.2"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.tukaani:xz:1.9")
    testImplementation("junit:junit:4.13.2")
}