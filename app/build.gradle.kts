plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dilip.carobd"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dilip.carobd"
        minSdk = 24
        targetSdk = 34
        versionCode = 22
        versionName = "4.9.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Android Auto (Car App Library) – the only external library
    implementation("androidx.car.app:app:1.4.0")
}
