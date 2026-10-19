package com.vivo.lpa

import android.util.Log
import java.io.File

/**
 * Limits NPU duty cycle so indexing can finish instead of being thermally killed.
 *
 * Measured on SM8850: vivo's daemonService force-stops the process once the Hexagon
 * sensors report 99.7C, even at oom adj 0 with a foreground service running. A full
 * 102-asset pass driven back to back reaches that point in about six minutes.
 *
 * A purely reactive guard does not work here. Checking the temperature only before each
 * asset is too coarse: a single inference runs 15-20s, and the sensors were observed to
 * climb from 74C to 100C between two checks, so the process was killed while the guard
 * still believed everything was fine. Generation also cannot be interrupted mid-flight.
 *
 * So the primary control is proactive -- count inferences and rest on a fixed cadence --
 * with the temperature reading used to decide when a rest has gone on long enough, and as
 * a secondary brake if a batch still runs hot.
 *
 * Counting is per genie-app invocation rather than per asset, because one asset can spend
 * up to four invocations on seed retries. Counting assets would let a retry-heavy batch
 * emit several times the heat the cadence was sized for.
 *
 * Sensors are located by type name rather than thermal_zone number, because the numbering
 * is not stable across devices or boots.
 */
internal class ThermalGuard(
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val pauseAboveCelsius: Float = DEFAULT_PAUSE_C,
    private val resumeBelowCelsius: Float = DEFAULT_RESUME_C,
    private val pollIntervalMs: Long = DEFAULT_POLL_MS
) {
    private val sensorPaths: List<File> by lazy { discoverSensors() }

    private var sinceRest = 0

    /** Sensor count, exposed so callers can report whether throttling is actually armed. */
    val sensorCount: Int get() = sensorPaths.size

    /** Highest NPU sensor reading, or null when no sensor could be read. */
    fun npuCelsius(): Float? {
        val readings = sensorPaths.mapNotNull { readCelsius(it) }
        return readings.maxOrNull()
    }

    /**
     * Records one genie-app invocation and reports whether a rest is now due.
     *
     * Call this immediately before spawning the runner, so the rest happens between
     * inferences rather than in the middle of one.
     */
    fun recordInference(): Boolean {
        sinceRest++
        return sinceRest >= batchSize
    }

    /** True once the NPU is hot enough that starting another inference risks a kill. */
    fun shouldThrottle(): Boolean {
        val current = npuCelsius() ?: return false
        return current >= pauseAboveCelsius
    }

    /**
     * Blocks until the NPU cools below the resume threshold, then re-arms the batch counter.
     *
     * Pausing at [pauseAboveCelsius] but only resuming at [resumeBelowCelsius] is what
     * keeps this from oscillating around a single threshold.
     *
     * Returns false if the caller asked to stop. Returns true when it is safe to proceed,
     * including when no sensor is readable or the wait cap is hit -- on a device that
     * exposes no matching sensor, stalling forever would be worse than being killed and
     * resumed, since completed work is checkpointed in the database either way.
     */
    fun awaitCooldown(
        cancelled: () -> Boolean = { false },
        onWaiting: (Float, Long) -> Unit = { _, _ -> }
    ): Boolean {
        sinceRest = 0
        var waitedMs = 0L
        while (true) {
            if (cancelled()) return false
            val current = npuCelsius() ?: return true
            if (current < resumeBelowCelsius) {
                if (waitedMs > 0) {
                    Log.i("ThermalGuard", "cooled to ${current}C after ${waitedMs / 1000}s")
                }
                return true
            }
            if (waitedMs >= MAX_WAIT_MS) {
                Log.w("ThermalGuard", "cooldown cap reached at ${current}C, resuming anyway")
                return true
            }
            onWaiting(current, waitedMs / 1000L)
            try {
                Thread.sleep(pollIntervalMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            waitedMs += pollIntervalMs
        }
    }

    private fun discoverSensors(): List<File> {
        val root = File("/sys/class/thermal")
        val zones = root.listFiles { file -> file.name.startsWith("thermal_zone") } ?: return emptyList()
        return zones.mapNotNull { zone ->
            val type = runCatching { File(zone, "type").readText().trim() }.getOrNull()
                ?: return@mapNotNull null
            if (NPU_SENSOR_TYPES.any { type.startsWith(it) }) File(zone, "temp") else null
        }.filter { it.canRead() }
    }

    private fun readCelsius(path: File): Float? = runCatching {
        path.readText().trim().toLong() / 1000f
    }.getOrNull()

    private companion object {
        /** Hexagon vector and matrix unit sensors, the ones that heat up under QNN load. */
        val NPU_SENSOR_TYPES = listOf("nsphmx", "nsphvx")

        /**
         * Inferences per batch. Sized from the measured ~2.1C rise per inference: six of
         * them add roughly 13C, so a batch starting at the 60C resume point peaks near
         * 73C, well clear of the 99.7C kill point.
         */
        const val DEFAULT_BATCH_SIZE = 6

        /** Secondary brake. Deliberately below the 85C used in the first attempt, which */
        /* was high enough that one more inference carried the sensors past the kill point. */
        const val DEFAULT_PAUSE_C = 75f

        /** Resume only after a real drop, so the loop does not chatter. */
        const val DEFAULT_RESUME_C = 60f

        const val DEFAULT_POLL_MS = 5_000L

        /**
         * Bound on a single rest. Idle cooling was measured at roughly 100C to 54C in
         * under two minutes, so five minutes is generous; the cap exists so indexing can
         * never stall indefinitely on an unexpected reading.
         */
        const val MAX_WAIT_MS = 5 * 60_000L
    }
}
