package io.github.martinzitka.trailog.ui.format

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.IceSkating
import androidx.compose.material.icons.filled.Kayaking
import androidx.compose.material.icons.filled.Kitesurfing
import androidx.compose.material.icons.filled.NordicWalking
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.RollerSkating
import androidx.compose.material.icons.filled.Rowing
import androidx.compose.material.icons.filled.Sledding
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
    ActivityType.CROSS_COUNTRY_SKIING -> R.string.activity_type_cross_country_skiing
    ActivityType.DOWNHILL_SKIING -> R.string.activity_type_downhill_skiing
    ActivityType.SWIMMING -> R.string.activity_type_swimming
    ActivityType.CANOEING -> R.string.activity_type_canoeing
    ActivityType.RAFTING -> R.string.activity_type_rafting
    ActivityType.INLINE_SKATING -> R.string.activity_type_inline_skating
    ActivityType.ICE_SKATING -> R.string.activity_type_ice_skating
    ActivityType.SNOWKITING -> R.string.activity_type_snowkiting
    ActivityType.BOBSLEIGH -> R.string.activity_type_bobsleigh
    ActivityType.OTHER -> R.string.activity_type_other
}

@Composable
fun ActivityType.label(): String = stringResource(labelRes())

fun ActivityType.icon(): ImageVector = when (this) {
    ActivityType.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityType.MOUNTAIN_BIKING -> Icons.Filled.Terrain
    ActivityType.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.HIKING -> Icons.Filled.Hiking
    ActivityType.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityType.CROSS_COUNTRY_SKIING -> Icons.Filled.NordicWalking
    ActivityType.DOWNHILL_SKIING -> Icons.Filled.DownhillSkiing
    ActivityType.SWIMMING -> Icons.Filled.Pool
    ActivityType.CANOEING -> Icons.Filled.Kayaking
    ActivityType.RAFTING -> Icons.Filled.Rowing
    ActivityType.INLINE_SKATING -> Icons.Filled.RollerSkating
    ActivityType.ICE_SKATING -> Icons.Filled.IceSkating
    ActivityType.SNOWKITING -> Icons.Filled.Kitesurfing
    ActivityType.BOBSLEIGH -> Icons.Filled.Sledding
    ActivityType.OTHER -> Icons.Filled.Category
}
