plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :core is pure Kotlin/JVM. It must never depend on Android or Ktor — see CLAUDE.md.
// A test (NoPlatformLeakTest) asserts this against the actual classpath.

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `api`, not `implementation`: both types appear in :core's public API — RawPoint.time and
    // RecordingSession.startTime are kotlinx.datetime.Instant, and RecordingEngine.session is a
    // kotlinx.coroutines StateFlow — so consumers (:app, :server) must see them transitively.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

tasks.test {
    useJUnitPlatform()
}
