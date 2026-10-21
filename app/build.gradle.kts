plugins {
    id("com.android.application")
}

android {
    namespace = "com.anmol.crate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anmol.crate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
