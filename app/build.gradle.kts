plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.alinx.plume"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alinx.plume"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.4.$buildNumber"
    }

    // Clé de signature de développement (non secrète) : permet d'installer
    // chaque nouvelle version par-dessus la précédente sans désinstaller.
    signingConfigs {
        create("plume") {
            storeFile = rootProject.file("keystore/plume-dev.jks")
            storePassword = "plumedev"
            keyAlias = "plume"
            keyPassword = "plumedev"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("plume")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("plume")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
