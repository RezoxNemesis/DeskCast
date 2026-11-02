plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rezox.deskcast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rezox.deskcast"
        minSdk = 30
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // A fixed keystore committed to the repo so every CI build can update the previous one in place.
        create("deskcast") {
            storeFile = file("deskcast.keystore")
            storePassword = "deskcast"
            keyAlias = "deskcast"
            keyPassword = "deskcast"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("deskcast")
        }
        debug {
            signingConfig = signingConfigs.getByName("deskcast")
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
    // Shizuku: lets DeskCast unlock itself on the phone via Wireless debugging (no computer needed).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
