import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// Signing
//
// The release keystore lives in `keystore/compass-release.jks` and its
// passwords in `keystore/keystore.properties`. Both files are committed on
// purpose: this is a personal, single-device vault, and the user explicitly
// asked to be able to rebuild the APK at any time.
//
// !! NEVER LOSE THE KEYSTORE !!
// If the keystore is lost you can never publish an update to an installed
// app - the only option is to uninstall it, which deletes every file inside
// the vault forever. See docs/SECURITY.md.
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.compass.vault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.compass.vault"
        minSdk = 26          // Android 8.0 Oreo
        targetSdk = 35       // Android 15
        versionCode = 3
        versionName = "0.2"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false        // keep the release build byte-for-byte reproducible
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }

    androidResources {
        // The bundled Inter font is ~860 KB; keep it uncompressed so the
        // platform can mmap it straight from the APK.
        noCompress += "ttf"
    }
}

dependencies {
    // The ONLY third-party dependency in the whole app.
    // Everything else is the Android framework itself, which keeps the
    // attack surface (and the supply chain) as small as possible.
    implementation(libs.kotlinx.coroutines.core)
}
