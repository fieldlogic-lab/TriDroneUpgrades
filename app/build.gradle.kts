plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.massisolutions.tridrone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.massisolutions.tridrone"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(1000) ?: 3
        versionName = "0.3.0"
    }

    signingConfigs {
        create("persistentDebug") {
            storeFile = file(System.getenv("TRIDRONE_KEYSTORE_PATH") ?: "tridrone-not-configured.jks")
            storePassword = System.getenv("TRIDRONE_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("TRIDRONE_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("TRIDRONE_KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        getByName("debug") {
            if (!System.getenv("TRIDRONE_KEYSTORE_PATH").isNullOrBlank())
                signingConfig = signingConfigs.getByName("persistentDebug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
