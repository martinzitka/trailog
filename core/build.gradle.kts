plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :core is pure Kotlin/JVM. It must never depend on Android or Ktor — see CLAUDE.md.
// A test (NoPlatformLeakTest) asserts this against the actual classpath.

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

tasks.test {
    useJUnitPlatform()
}
