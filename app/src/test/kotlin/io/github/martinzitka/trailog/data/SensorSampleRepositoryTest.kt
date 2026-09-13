package io.github.martinzitka.trailog.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.martinzitka.trailog.core.model.SensorSample
import io.github.martinzitka.trailog.core.model.SensorStream
import io.github.martinzitka.trailog.core.model.SensorType
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sensor sample stream's storage half: readings go in as they arrive, come back out attached to
 * the activity they belong to, and go away with it.
 *
 * Runs against a real SQLite database under Robolectric, so foreign keys and the cascade are the
 * genuine article rather than a stubbed promise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensorSampleRepositoryTest {

    private lateinit var db: TrailogDatabase
    private lateinit var repository: ActivityRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailogDatabase::class.java,
        ).build()
        repository = ActivityRepository(db) { 999L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun samples_roundTripThroughTheDatabase() = runTest {
        seedActivity()
        val written = listOf(
            hr(1_000, 142.0),
            hr(2_000, 145.0),
            SensorSample(Instant.fromEpochMilliseconds(1_000), SensorType.CADENCE, 85.0),
        )

        repository.addSamples(ID, written)

        val loaded = repository.loadActivity(ID)!!.samples
        assertEquals(3, loaded.size)
        assertTrue(loaded.containsAll(written))
        assertEquals(3, repository.sampleCount(ID))
    }

    @Test
    fun samples_areAppended_neverReplaced() = runTest {
        // Readings are raw data, as immutable as the fixes beside them. A second write from a
        // later source must not silently discard what the first one recorded.
        seedActivity()
        repository.addSamples(ID, listOf(hr(1_000, 142.0)))
        repository.addSamples(ID, listOf(hr(2_000, 145.0)))

        assertEquals(listOf(142.0, 145.0), repository.loadActivity(ID)!!.samples.map { it.value })
    }

    @Test
    fun samples_writtenOutOfOrder_comeBackInTimeOrder() = runTest {
        // Fixes are assumed to arrive out of order (CLAUDE.md); so are readings.
        seedActivity()
        repository.addSamples(ID, listOf(hr(3_000, 150.0), hr(1_000, 142.0), hr(2_000, 145.0)))

        assertEquals(
            listOf(142.0, 145.0, 150.0),
            repository.loadActivity(ID)!!.samples.map { it.value },
        )
    }

    @Test
    fun aDuplicateReading_isStoredAndCollapsedOnlyByTheReadingView() = runTest {
        // Deliberately no uniqueness constraint: the row is the honest record of what the sensor
        // sent. De-duplication is a view over the data, exactly as it is for raw points — the
        // loader hands back what is stored, and SensorStream collapses it for whoever reads it.
        seedActivity()
        repository.addSamples(ID, listOf(hr(1_000, 142.0), hr(1_000, 999.0), hr(2_000, 145.0)))

        val loaded = repository.loadActivity(ID)!!.samples
        assertEquals(3, loaded.size)
        assertEquals(3, repository.sampleCount(ID))
        assertEquals(2, SensorStream.normalise(loaded).size)
    }

    @Test
    fun samples_areKeptPerActivity() = runTest {
        seedActivity(ID)
        seedActivity(OTHER)
        repository.addSamples(ID, listOf(hr(1_000, 142.0)))
        repository.addSamples(OTHER, listOf(hr(1_000, 120.0)))

        assertEquals(listOf(142.0), repository.loadActivity(ID)!!.samples.map { it.value })
        assertEquals(listOf(120.0), repository.loadActivity(OTHER)!!.samples.map { it.value })
    }

    @Test
    fun deletingAnActivity_takesItsSamplesWithIt() = runTest {
        // The same reasoning as raw points: keeping readings from a ride the user deleted would be
        // holding data they believe is gone.
        seedActivity()
        repository.addSamples(ID, listOf(hr(1_000, 142.0), hr(2_000, 145.0)))

        assertTrue(repository.delete(ID))

        assertEquals(0, repository.sampleCount(ID))
        assertNull(repository.loadActivity(ID))
    }

    @Test
    fun writingNoSamples_doesNothing() = runTest {
        seedActivity()
        repository.addSamples(ID, emptyList())
        assertEquals(0, repository.sampleCount(ID))
    }

    @Test
    fun aRowNamingAnUnknownSensor_isSkippedRatherThanBreakingTheRead() = runTest {
        // Only reachable after a downgrade. Losing one unknown stream beats making every activity
        // that contains it unopenable.
        seedActivity()
        repository.addSamples(ID, listOf(hr(1_000, 142.0)))
        db.sensorSampleDao().insert(
            SensorSampleEntity(activityId = ID, time = 1_500, type = "MUSCLE_OXYGEN", value = 62.5),
        )

        assertEquals(listOf(142.0), repository.loadActivity(ID)!!.samples.map { it.value })
        assertEquals(2, repository.sampleCount(ID))
    }

    private suspend fun seedActivity(id: String = ID) {
        db.activityDao().upsert(
            ActivityEntity(
                id = id,
                type = "CYCLING",
                name = "",
                notes = null,
                startTime = 1_000,
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
    }

    private fun hr(millis: Long, value: Double) =
        SensorSample(Instant.fromEpochMilliseconds(millis), SensorType.HEART_RATE, value)

    private companion object {
        const val ID = "019fdd0d-0000-7000-8000-0000000000a1"
        const val OTHER = "019fdd0d-0000-7000-8000-0000000000a2"
    }
}
