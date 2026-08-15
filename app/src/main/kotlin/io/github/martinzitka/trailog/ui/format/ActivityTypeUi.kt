package io.github.martinzitka.trailog.ui.format

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.core.model.ActivityType

/**
 * Display mapping for [ActivityType] — the label prose lives in string resources (never
 * hardcoded), the icon is a Material symbol. Adding a type is a new `when` arm here plus a
 * string, nothing structural.
 */

@StringRes
fun ActivityType.labelRes(): Int = when (this) {
    ActivityType.CYCLING -> R.string.activity_type_cycling
    ActivityType.MOUNTAIN_BIKING -> R.string.activity_type_mountain_biking
    ActivityType.RUNNING -> R.string.activity_type_running
    ActivityType.HIKING -> R.string.activity_type_hiking
    ActivityType.WALKING -> R.string.activity_type_walking
}

@Composable
fun ActivityType.label(): String = stringResource(labelRes())

fun ActivityType.icon(): ImageVector = when (this) {
    ActivityType.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityType.MOUNTAIN_BIKING -> Icons.Filled.Terrain
    ActivityType.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.HIKING -> Icons.Filled.Hiking
    ActivityType.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
}
