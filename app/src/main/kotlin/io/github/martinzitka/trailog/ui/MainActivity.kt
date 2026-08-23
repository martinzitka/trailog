package io.github.martinzitka.trailog.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import io.github.martinzitka.trailog.data.ActivityRepository
import io.github.martinzitka.trailog.data.TrailogDatabase
import io.github.martinzitka.trailog.recording.AndroidRecordingEngine
import io.github.martinzitka.trailog.ui.nav.TrailogApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The single Activity. Hosts the Compose navigation scaffold ([TrailogApp]) — a bottom bar over
 * Record, History, Sensors and Settings, with Activity detail pushed from History.
 *
 * On every resume it asks the recording engine to reconcile any persisted session against the
 * recovery policy, so an interrupted activity is surfaced on the Record screen on next open
 * (CLAUDE.md / ADR 0004). The old M1.3 diagnostics harness has been replaced by the real Record
 * screen.
 */
class MainActivity : ComponentActivity() {

    private val engine by lazy { AndroidRecordingEngine.get(this) }
    private val repository by lazy { ActivityRepository(TrailogDatabase.get(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a genuine cold start, not on every configuration change.
        if (savedInstanceState == null) backfillMissingStats()
        setContent {
            TrailogApp()
        }
    }

    override fun onResume() {
        super.onResume()
        engine.reconcileOnAppOpen()
    }

    /**
     * Fill in statistics for activities that have none — the recordings the 1 → 2 migration
     * created from raw points, which no [ActivityRepository.recompute] call has ever covered
     * because that only runs when an activity is finalised. Until this runs, History shows their
     * figures as pending.
     *
     * Off the main thread (the statistics pass is real CPU work over every raw point) and
     * fire-and-forget: the cache is derived data, so a run cut short by the activity going away
     * costs nothing but a retry next launch. Nothing is logged — no counts, no ids, no
     * coordinates.
     */
    private fun backfillMissingStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.recomputeMissing()
        }
    }
}
