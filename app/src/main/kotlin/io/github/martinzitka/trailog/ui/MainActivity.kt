package io.github.martinzitka.trailog.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.martinzitka.trailog.recording.AndroidRecordingEngine
import io.github.martinzitka.trailog.ui.nav.TrailogApp

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrailogApp()
        }
    }

    override fun onResume() {
        super.onResume()
        engine.reconcileOnAppOpen()
    }
}
