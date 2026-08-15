plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// M1 Android app. The M0 spike is gone; this is real code. Packages:
//   data/      Room persistence (raw_points, recording_sessions), PRAGMA synchronous = FULL
//   recording/ the RecordingEngine implementation, foreground service, boot recovery
//   ui/        Compose screens (M1.5): navigation scaffold, theme, formatters, Record; the
//              interim M1.3 harness has been replaced by the real Record screen

android {
    namespace = "io.github.martinzitka.trailog"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.martinzitka.trailog"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0-m0"
    }

    buildTypes {
        debug {
            // A debug build and the real recording app must coexist on one device
            // (CLAUDE.md: agents throw experimental builds at the same phone).
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric drives real SQLite in JVM unit tests, so Room migration and recompute
        // tests run on `./gradlew check` / CI without an emulator.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        warningsAsErrors = false
        // "A newer version exists" nags — dependency freshness is Renovate's job, not the
        // build's. These would otherwise fire on every module forever.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
        // Intentional for the throwaway recording spike: a GPS tracker really does require
        // GPS; the battery-optimisation exemption is a deliberate durability requirement
        // (CLAUDE.md); backup is disabled so extraction rules are moot.
        disable += setOf("UnnecessaryRequiredFeature", "BatteryLife", "DataExtractionRules")
        // -v26 is the conventional adaptive-icon folder (AAPT requires the version
        // qualifier); the "redundant since minSdk 26" nag is cosmetic.
        disable += setOf("ObsoleteSdkInt")
        // The "N fixes" diagnostic counter in the recording notification is a live debug
        // readout, not prose that needs pluralisation. Real user-facing strings arrive in M1.5.
        disable += setOf("PluralsCandidate")
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose UI tests run as JVM unit tests under Robolectric (unitTests.isIncludeAndroidResources).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Room schema JSON is exported here and committed; migration tests validate against it and it
// records the schema history (CLAUDE.md: migrations forward-only, checked in).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
