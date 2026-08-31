package io.github.martinzitka.trailog.tools.sportstracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The list endpoint's response shape is undocumented and the community scripts describing it are
 * years old. These tests pin the tolerance that buys against that: find the workouts wherever
 * they are, and never throw away the run because one field moved.
 */
class WorkoutIndexTest {

    @Test
    fun `workouts are found inside the payload envelope community scripts describe`() {
        val body = """
            {"payload":[
              {"workoutKey":"abc123","startTime":1600000000000,"totalDistance":38030.0,"totalTime":7200},
              {"workoutKey":"def456","startTime":1600100000000,"totalDistance":12000.0,"totalTime":3600}
            ],"metadata":{}}
        """.trimIndent()

        val refs = WorkoutIndex.parse(body)
        assertEquals(listOf("abc123", "def456"), refs.map { it.key })
        assertEquals("38030.0", refs[0].distanceRaw)
        assertEquals("7200", refs[0].durationRaw)
    }

    @Test
    fun `a bare array of workouts parses too`() {
        val refs = WorkoutIndex.parse("""[{"workoutKey":"abc"},{"workoutKey":"def"}]""")
        assertEquals(2, refs.size)
    }

    @Test
    fun `the workout list wins over a shorter incidental array`() {
        // The heuristic that survives the envelope being renamed: biggest array of objects wins.
        val body = """
            {"pagination":[{"page":1}],
             "somethingElse":[
               {"workoutKey":"a"},{"workoutKey":"b"},{"workoutKey":"c"}
             ]}
        """.trimIndent()
        assertEquals(listOf("a", "b", "c"), WorkoutIndex.parse(body).map { it.key })
    }

    @Test
    fun `alternative key field names are accepted`() {
        assertEquals("k1", WorkoutIndex.parse("""[{"key":"k1"}]""").single().key)
        assertEquals("k2", WorkoutIndex.parse("""[{"id":"k2"}]""").single().key)
        assertEquals("k3", WorkoutIndex.parse("""[{"_id":"k3"}]""").single().key)
    }

    @Test
    fun `workoutKey wins when several candidate fields are present`() {
        val refs = WorkoutIndex.parse("""[{"id":"internal","workoutKey":"theRealOne"}]""")
        assertEquals("theRealOne", refs.single().key)
    }

    @Test
    fun `records with no recognisable key are skipped rather than failing the parse`() {
        val body = """[{"workoutKey":"good"},{"unrelated":"record"},{"workoutKey":""}]"""
        assertEquals(listOf("good"), WorkoutIndex.parse(body).map { it.key })
    }

    @Test
    fun `a null field does not become the string null`() {
        val refs = WorkoutIndex.parse("""[{"workoutKey":"a","totalDistance":null}]""")
        assertEquals(null, refs.single().distanceRaw)
    }

    @Test
    fun `malformed JSON yields no workouts instead of throwing`() {
        assertTrue(WorkoutIndex.parse("<html>login</html>").isEmpty())
        assertTrue(WorkoutIndex.parse("").isEmpty())
    }

    @Test
    fun `an index round-trips through TSV`() {
        val refs = listOf(
            WorkoutRef("abc", "1600000000000", "1", "38030.0", "7200"),
            WorkoutRef("def", null, null, null, null),
        )
        val tsv = WorkoutIndex.toTsv(refs)
        assertEquals(listOf("abc", "def"), WorkoutIndex.keysFromTsv(tsv))
    }

    @Test
    fun `the TSV header row is not mistaken for a workout`() {
        val tsv = WorkoutIndex.toTsv(listOf(WorkoutRef("abc", null, null, null, null)))
        assertFalse(WorkoutIndex.keysFromTsv(tsv).contains("key"))
    }

    @Test
    fun `a tab or newline in a value cannot break the column layout`() {
        val refs = listOf(WorkoutRef("abc", "a\tb", "c\nd", null, null))
        val tsv = WorkoutIndex.toTsv(refs)
        val dataLine = tsv.lineSequence().drop(1).first()
        assertEquals(6, dataLine.count { it == '\t' }, "expected exactly seven columns")
        assertEquals(listOf("abc"), WorkoutIndex.keysFromTsv(tsv))
    }

    @Test
    fun `an empty index yields no keys`() {
        assertTrue(WorkoutIndex.keysFromTsv(WorkoutIndex.toTsv(emptyList())).isEmpty())
    }

    @Test
    fun `a manually added workout is recognised`() {
        // The field that separates "never had a track" from "lost its track" — see ArchiveAudit.
        val refs = WorkoutIndex.parse("""[{"workoutKey":"a","isManuallyAdded":true}]""")
        assertTrue(refs.single().manuallyAdded)
    }

    @Test
    fun `a recorded workout is not marked as manually added`() {
        assertFalse(WorkoutIndex.parse("""[{"workoutKey":"a"}]""").single().manuallyAdded)
        assertFalse(
            WorkoutIndex.parse("""[{"workoutKey":"a","isManuallyAdded":false}]""")
                .single().manuallyAdded,
        )
    }

    @Test
    fun `track presence follows the polyline`() {
        assertTrue(WorkoutIndex.parse("""[{"workoutKey":"a","polyline":"_p~iF~ps"}]""").single().hasTrack)
        assertFalse(WorkoutIndex.parse("""[{"workoutKey":"a","polyline":""}]""").single().hasTrack)
        assertFalse(WorkoutIndex.parse("""[{"workoutKey":"a"}]""").single().hasTrack)
    }
}
