plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions sets GITHUB_RUN_NUMBER, so every build gets a higher version
// and installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "dev.jevassist"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.jevassist"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // A fixed key (committed in app/) so each new APK can update the installed one.
    // It only signs your personal sideloaded app; keep the repo private.
    signingConfigs {
        create("shared") {
            storeFile = file("jevassist.keystore")
            storePassword = "jevassist"
            keyAlias = "jevassist"
            keyPassword = "jevassist"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
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
// No third-party dependencies: networking, JSON, speech and TTS all come from Android itself.
