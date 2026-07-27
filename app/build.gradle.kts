plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.nisarg.paisa"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.nisarg.paisa"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-phase1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Deliberately no AndroidX, no Compose, no Room.
    // Phase 1 needs a listener, a DB and one debug screen. Plain platform APIs
    // keep the dependency tree tiny and the build reliable on a CLI toolchain.
    testImplementation("junit:junit:4.13.2")
}
