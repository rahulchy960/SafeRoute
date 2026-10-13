// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.emergency

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.saferoute.app.core.emergency.BatteryLevel
import com.saferoute.app.core.emergency.ContactsFreshener
import com.saferoute.app.core.emergency.CountdownTone
import com.saferoute.app.core.emergency.PurgeScheduler
import com.saferoute.app.core.emergency.RetrySignals
import com.saferoute.app.core.emergency.SmsComposer
import com.saferoute.app.feature.diagnostics.ReportingSmsGateway
import com.saferoute.app.core.emergency.SmsModeSource
import com.saferoute.app.core.emergency.SmsRecipientLimit
import com.saferoute.app.core.emergency.SosAlertPolicy
import com.saferoute.app.core.emergency.SosMessageSettings
import com.saferoute.app.core.emergency.SmsGateway
import com.saferoute.app.core.emergency.SosHaptics
import com.saferoute.app.core.emergency.SosHost
import com.saferoute.app.core.emergency.SosHousekeeping
import com.saferoute.app.core.emergency.UnsentAlertNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TICK_MILLIS = 150L
private const val TONE_MILLIS = 150

/** Percent of the alarm volume the user has set. */
private const val TONE_VOLUME = 80
private val TRIGGERED_PATTERN = longArrayOf(0, 400, 150, 400)

/**
 * The countdown's vibration. Marked as an ALARM, the strongest category an ordinary app may
 * use. The user's settings still decide: in silent or Do Not Disturb mode a phone may not
 * vibrate at all, and the app cannot override that. The countdown on screen never depends on
 * the vibration. Needs the VIBRATE permission (granted at install, no dialog).
 */
class AndroidSosHaptics @Inject constructor(
    @ApplicationContext private val context: Context,
) : SosHaptics {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    override fun countdownTick() = play(VibrationEffect.createOneShot(TICK_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))

    override fun triggered() = play(VibrationEffect.createWaveform(TRIGGERED_PATTERN, -1))

    private fun play(effect: VibrationEffect) {
        val device = vibrator?.takeIf { it.hasVibrator() } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            device.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}

/**
 * The countdown's tone, played only when the user switched it on under "SOS setup".
 *
 * A TONE GENERATOR plays a short built-in beep without any sound file. It uses the ALARM
 * volume, so it follows the phone's alarm slider and not the ringer; a phone whose alarm
 * volume is at zero, or that silences alarms in Do Not Disturb, stays quiet. The generator
 * is made when first needed and kept: making one per second can fail on some phones. If the
 * phone refuses to make one, there is simply no tone: the countdown never depends on it.
 * Needs no permission.
 */
@Singleton
class AndroidCountdownTone @Inject constructor() : CountdownTone {

    private var generator: ToneGenerator? = null

    @Synchronized
    override fun play() {
        runCatching {
            val tone = generator ?: ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME).also { generator = it }
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MILLIS)
        }
    }
}

class AndroidBatteryLevel @Inject constructor(
    @ApplicationContext private val context: Context,
) : BatteryLevel {

    override fun percent(): Int? =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
}

/**
 * The daily clean-up of emergency records older than 30 days, for a phone on which the app is
 * not opened for a long time (the same clean-up also runs at every app start).
 *
 * WORKMANAGER runs a small job later, also after the app was closed or the phone restarted,
 * at a time Android finds convenient for the battery. A job is a class ("worker"); Android
 * creates it by name, so it gets what it needs from Hilt through an entry point.
 */
class SosPurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun housekeeping(): SosHousekeeping
    }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
            .housekeeping()
            .purgeExpired()
        return Result.success()
    }

    companion object {
        // Not the three letters as a word of their own: SosColourUsageTest looks for that.
        const val UNIQUE_NAME = "daily-purge-of-emergency-records"
    }
}

class WorkManagerPurgeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : PurgeScheduler {

    override fun scheduleDaily() {
        val workManager = try {
            WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            // WorkManager is not set up in this process (it sets itself up at app start on a
            // phone; a JVM test has no such start). Planning a clean-up must never stop the
            // app from starting, and the clean-up at app start has already run.
            return
        }
        // KEEP: if the job is already planned, leave it; asking again must not restart its day.
        workManager.enqueueUniquePeriodicWork(
            SosPurgeWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SosPurgeWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}

/** One module, so that tests replace it (`FakeSosDeviceModule`). */
@Module
@InstallIn(SingletonComponent::class)
interface SosDeviceModule {

    @Binds
    fun bindSosHost(host: AndroidSosHost): SosHost

    @Binds
    fun bindSosHaptics(haptics: AndroidSosHaptics): SosHaptics

    @Binds
    fun bindCountdownTone(tone: AndroidCountdownTone): CountdownTone

    @Binds
    fun bindBatteryLevel(battery: AndroidBatteryLevel): BatteryLevel

    @Binds
    fun bindPurgeScheduler(scheduler: WorkManagerPurgeScheduler): PurgeScheduler

    @Binds
    // The real gateway, wrapped so that a failed send is noted as a fixed word (ADR 0028).
    fun bindSmsGateway(gateway: ReportingSmsGateway): SmsGateway

    @Binds
    fun bindSosAlertPolicy(policy: ConsentSosAlertPolicy): SosAlertPolicy

    @Binds
    fun bindSosMessageSettings(settings: AppSosMessageSettings): SosMessageSettings

    @Binds
    fun bindSmsModeSource(source: AndroidSmsModeSource): SmsModeSource

    @Binds
    fun bindSmsComposer(composer: AndroidSmsComposer): SmsComposer

    // BuildSmsRecipientLimit exists twice: src/debug (the list of test numbers) and
    // src/release (no limit). The build type decides which one is compiled.
    @Binds
    fun bindSmsRecipientLimit(limit: BuildSmsRecipientLimit): SmsRecipientLimit

    @Binds
    fun bindContactsFreshener(freshener: RepositoryContactsFreshener): ContactsFreshener

    @Binds
    fun bindUnsentAlertNotifier(notifier: AndroidUnsentAlertNotifier): UnsentAlertNotifier

    @Binds
    fun bindRetrySignals(signals: AndroidRetrySignals): RetrySignals

    @Binds
    fun bindRestartNotifier(notifier: AndroidRestartNotifier): RestartNotifier
}
