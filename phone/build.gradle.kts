plugins {
    id("com.android.application")
}

android {
    namespace = "dev.clickety"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.clickety"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
    }

    signingConfigs {
        // Release APKs are signed with the local debug key so they sideload without extra setup.
        create("localRelease") {
            storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("localRelease")
                ?.takeIf { it.storeFile?.exists() == true }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
