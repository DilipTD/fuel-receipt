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
        versionCode = 35
        versionName = "4.20.0"
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                it.systemProperty("robolectric.logging.enabled", "false")
                it.testLogging { events("failed", "skipped"); showStandardStreams = false }
            }
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Android Auto (Car App Library) – the only external library
    implementation("androidx.car.app:app:1.4.0")
    // simulation tests (run on CI): Robolectric runs the real app on the JVM against an emulated ELM327 car
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
