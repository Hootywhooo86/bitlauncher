plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from env vars so no key ever lands in the repo.
val keystorePath: String? = System.getenv("BITLAUNCHER_KEYSTORE")

android {
    namespace = "com.hootywhooo86.bitlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hootywhooo86.bitlauncher"
        minSdk = 26
        targetSdk = 35
        // CI passes -PversionName/-PversionCode; this line is the source of truth for releases.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 100
        versionName = (project.findProperty("versionName") as String?) ?: "0.5.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("BITLAUNCHER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BITLAUNCHER_KEY_ALIAS")
                keyPassword = System.getenv("BITLAUNCHER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
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
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // No AndroidX, no UI toolkit: the framework only, so the home screen starts fast.
    testImplementation("junit:junit:4.13.2")
    // android.jar stubs org.json for unit tests; this is the real one.
    testImplementation("org.json:json:20240303")
    // Runs the real activities on the JVM to catch crashes without a phone. Tests only.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
