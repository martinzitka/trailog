plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// M2.1 — THROWAWAY. See README.md in this directory before touching anything here.
//
// Deliberately does *not* depend on :core. This tool only moves opaque bytes from a
// third-party endpoint onto the local disk; it never parses a track. Parsing is :tools:importer's
// job (M2.2), through :core's single GPX implementation. Keeping the scraper ignorant of the
// domain model is what lets it be deleted without leaving a hole.

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.martinzitka.trailog.tools.sportstracker.CliKt")
}

dependencies {
    // Generic JSON tree walking only — no @Serializable classes, so the compiler plugin is not
    // needed. The workout list's shape is undocumented and unverified; a schema-bound
    // deserializer would throw on the first unexpected field, which is exactly the wrong
    // failure mode for a scraper pointed at an endpoint nobody promised us.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
