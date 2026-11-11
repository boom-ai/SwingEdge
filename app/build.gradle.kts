plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Increases on every CI run so each build installs as an update over the previous one.
val ciVersionCode: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.boomai.swingedge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.boomai.swingedge"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
    }

    signingConfigs {
        // Fixed key committed to the repo on purpose: every CI build is signed identically,
        // so a new APK always installs over the old one without uninstalling.
        create("stable") {
            storeFile = file("swingedge-debug.jks")
            storePassword = "swingedge"
            keyAlias = "swingedge"
            keyPassword = "swingedge"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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

// Deliberately no dependencies: Kotlin stdlib (added by the Kotlin plugin) + Android framework only.
