import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.wire)
}

android {
    namespace = "com.wanderwildwood.amime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wanderwildwood.amime"
        // The Kompakt runs Android 12 (API 31); nothing here needs anything newer.
        minSdk = 31
        targetSdk = 31
        versionCode = 16
        versionName = "0.3.0-mt2"
    }

    // A real keystore in signing/ signs every build type when it is present, so the
    // very first install is already release-signed and a later update can never hit
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE. It is gitignored, and there is no fallback:
    // a fresh clone builds an unsigned release APK, which will not install anywhere.
    val signingPropertiesFile = rootProject.file("signing/signing.properties")
    val realSigningConfig = if (signingPropertiesFile.isFile) {
        val signingProperties = Properties().apply {
            signingPropertiesFile.inputStream().use(::load)
        }
        signingConfigs.create("real") {
            storeFile = rootProject.file("signing/signing.keystore")
            storePassword = signingProperties.getProperty("STORE_PASSWORD")
            keyAlias = signingProperties.getProperty("KEY_ALIAS")
            keyPassword = signingProperties.getProperty("KEY_PASSWORD")
        }
    } else {
        null
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            realSigningConfig?.let { signingConfig = it }
        }
        getByName("release") {
            // AGP otherwise stamps the git revision of the build into the APK, which
            // publishes a little more about how a release was made than a release needs to.
            vcsInfo {
                include = false
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            realSigningConfig?.let { signingConfig = it }
        }
    }

    lint {
        // Sideloaded onto a Kompakt, not going to Google Play, whose API-33 floor this
        // otherwise trips. Targeting the OS the device actually runs is deliberate.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // The About dialog shows the version it is actually running.
        buildConfig = true
    }

    sourceSets {
        named("main") { kotlin.srcDir("src/main/kotlin") }
        named("test") { kotlin.srcDir("src/test/kotlin") }
    }
}

// The Meshtastic protocol definitions are vendored under src/main/proto (see NOTICE there)
// and compiled to Kotlin here. Only what the app sends and reads is kept; the rest of the
// definitions are pruned away so the APK does not carry them.
wire {
    sourcePath {
        srcDir("src/main/proto")
    }
    root(
        "meshtastic.ToRadio",
        "meshtastic.FromRadio",
        "meshtastic.AdminMessage",
        "meshtastic.Routing",
        "meshtastic.Position",
        "meshtastic.User",
        "meshtastic.Telemetry",
        "meshtastic.ChannelSet",
    )
    // The nanopb options are for the firmware's C code, not for this one.
    prune("NanoPBOptions")
    kotlin {
    }
}

dependencies {
    implementation(libs.wire.runtime)
    // Draws a channel link as a QR code. Reading one is the Camera app's job, not this app's.
    implementation(libs.zxing.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.mmd)

    testImplementation(libs.junit)
}
