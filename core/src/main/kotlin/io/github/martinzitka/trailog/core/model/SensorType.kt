package io.github.martinzitka.trailog.core.model

/**
 * The unit a [SensorType]'s value is stored in. There is exactly **one** unit per sensor type,
 * fixed forever, and nothing converts anywhere except the client's formatter at the display edge.
 *
 * This is a symbol, not a label: no user-facing string lives here. The display edge maps a unit
 * onto a string resource, which is also where a future imperial preference would act.
 *
 * CLAUDE.md requires SI internally with no exceptions, and this enum is where that rule is
 * discharged rather than broken — see `docs/adr/0023-sensor-sample-units.md`. SI is used wherever
 * a meaningful SI unit exists; where the domain's unit is integral and SI is not, the domain unit
 * wins, because converting would *introduce* precision loss rather than prevent it.
 */
enum class SensorUnit {
    /**
     * Heart beats per minute. Deliberately not hertz: BLE reports an integer bpm, and 140 bpm is
     * 2.333… Hz, which does not round-trip — 139.99999 comes back.
     */
    BEATS_PER_MINUTE,

    /** Crank or wheel revolutions per minute. Integral at the source, for the same reason. */
    REVOLUTIONS_PER_MINUTE,

    /** Watts. SI, and what every power meter reports. */
    WATT,

    /** Degrees Celsius. What the sensors report and what both display options are built from. */
    DEGREE_CELSIUS,
}

/**
 * A kind of sensor reading Trailog stores as a timestamped sample.
 *
 * **Adding a sensor is a new constant here, never a migration.** Samples are stored generically
 * as (time, type, value), so a new sensor costs one line and no schema change. This mirrors the
 * criterion the Sensors screen already meets: adding a sensor type is adding a row, not
 * restructuring anything.
 *
 * **Values are persisted by enum name**, like [ActivityType]. Add freely; never rename or remove,
 * because a rename orphans every stored sample that used the old name.
 *
 * The four constants below are the sensors that standard GPX expresses per track point
 * (`gpxtpx:hr`, `gpxtpx:cad`, `gpxtpx:atemp`, and power). All four are read, because the
 * alternative is silently dropping a value that a file plainly contained — and unlike an
 * [ActivityType], a [SensorType] has no user-facing cost for existing unused. Only heart rate is
 * known to be present in the imported history, and only heart rate has a producer planned.
 *
 * @property unit the single unit this type's `value` is always in. See [SensorUnit].
 */
enum class SensorType(val unit: SensorUnit) {
    /** Beats per minute, as a BLE strap (GATT `0x180D`) and GPX `gpxtpx:hr` both report it. */
    HEART_RATE(SensorUnit.BEATS_PER_MINUTE),

    /** Pedalling or step cadence, as GPX `gpxtpx:cad` reports it. */
    CADENCE(SensorUnit.REVOLUTIONS_PER_MINUTE),

    /** Mechanical power at the pedals or equivalent. */
    POWER(SensorUnit.WATT),

    /**
     * Ambient air temperature, as GPX `gpxtpx:atemp` reports it. Distinct from
     * [RawPoint.pressure], which is a property of a location fix rather than its own stream.
     */
    TEMPERATURE(SensorUnit.DEGREE_CELSIUS),
    ;

    companion object {
        /** The type stored under [name], or null if this build does not know it. */
        fun byNameOrNull(name: String): SensorType? = entries.firstOrNull { it.name == name }
    }
}
