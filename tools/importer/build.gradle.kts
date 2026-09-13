plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// M2.2 — durable, unlike the M2.1 scraper next door. This one *is* allowed to know the domain:
// it parses tracks, and it does so through :core's single GPX implementation so that a track can
// never be interpreted two different ways (CLAUDE.md).

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.martinzitka.trailog.tools.importer.CliKt")
}

dependencies {
    implementation(project(":core"))

    // Generic JSON tree walking, for the same reason the scraper does it: the workout list's shape
    // is undocumented, and a schema-bound deserializer would throw on the first unexpected field.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
