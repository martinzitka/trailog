package io.github.martinzitka.trailog.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * :core must stay platform-free (CLAUDE.md): no android.* and no Ktor on its classpath, so
 * a statistic can never be computed two different ways. This test is the enforcement.
 *
 * It checks two things: that representative Android/Ktor classes cannot be loaded, and that
 * no dependency jar on the runtime classpath looks like an Android or Ktor artifact.
 */
class NoPlatformLeakTest {

    @Test
    fun `android and ktor classes are not on the classpath`() {
        for (fqcn in listOf(
            "android.content.Context",
            "android.location.Location",
            "androidx.room.Room",
            "io.ktor.server.application.Application",
        )) {
            assertFailsWith<ClassNotFoundException>("$fqcn must not be reachable from :core") {
                Class.forName(fqcn)
            }
        }
    }

    @Test
    fun `no android or ktor jars on the runtime classpath`() {
        val classpath = System.getProperty("java.class.path").orEmpty()
        val entries = classpath.split(java.io.File.pathSeparatorChar)
        val forbidden = entries.filter { entry ->
            val name = entry.substringAfterLast(java.io.File.separatorChar).lowercase()
            name.startsWith("ktor-") ||
                name.contains("android") ||
                name.startsWith("room-")
        }
        if (forbidden.isNotEmpty()) {
            fail("forbidden platform artifacts on :core classpath: $forbidden")
        }
    }
}
