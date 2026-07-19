plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// M0 RECORDING SPIKE — throwaway (see IMPLEMENTATION_PLAN.md M0).
// The only purpose of this module right now is to prove background GPS recording survives
// on real hardware. Written fast and deliberately without architecture. It will be
// deleted or rewritten for M1. Do not build features on top of it.

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
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
