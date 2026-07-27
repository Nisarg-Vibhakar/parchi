package dev.nisarg.paisa.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import dev.nisarg.paisa.work.Caller

/**
 * Makes it ring, in the voice of whoever is calling.
 *
 * A ringtone is not decoration here — it is the difference between an incoming
 * call and a dialog with a picture of a face on it. And since the caller's mood
 * is already computed from what the day cost, the ring can carry it too: a good
 * day taps twice and stops, a blown cycle produces a long, unhurried, faintly
 * threatening pulse.
 *
 * Uses only system sounds. Shipping audio assets for a joke would be absurd, and
 * the stock alarm tone is already the most stressful noise a phone can make.
 */
object Ringer {

    /**
     * Vibration patterns in milliseconds, alternating wait/buzz.
     *
     * Pure, so the shape of each mood is testable without a device — the phone
     * would only tell us it vibrated, never whether it felt like the right one.
     */
    fun patternFor(mood: Caller.Mood): LongArray = when (mood) {
        // Two light taps and done. Nothing is wrong; nothing needs urgency.
        Caller.Mood.JOY -> longArrayOf(0, 70, 140, 70, 1600)
        // The classic double ring. Neutral, unremarkable, easy to ignore.
        Caller.Mood.CALM -> longArrayOf(0, 280, 220, 280, 1400)
        // Slightly too long, slightly too soon. Faintly nagging.
        Caller.Mood.CONCERN -> longArrayOf(0, 400, 200, 400, 1000)
        // Rapid triple burst, the pattern of something that wants your attention now.
        Caller.Mood.ALARM -> longArrayOf(0, 180, 90, 180, 90, 180, 700)
        // Long, slow, unhurried. It is not in a rush because it has already won.
        Caller.Mood.DOOM -> longArrayOf(0, 900, 250, 900, 250)
    }

    /**
     * Which system sound suits the mood. The alarm tone for the bad end of the
     * scale, because it is the one noise nobody has learned to sleep through.
     */
    fun toneTypeFor(mood: Caller.Mood): Int = when (mood) {
        Caller.Mood.JOY, Caller.Mood.CALM -> RingtoneManager.TYPE_NOTIFICATION
        Caller.Mood.CONCERN -> RingtoneManager.TYPE_RINGTONE
        Caller.Mood.ALARM, Caller.Mood.DOOM -> RingtoneManager.TYPE_ALARM
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    fun start(context: Context, mood: Caller.Mood) {
        val audio = context.getSystemService(AudioManager::class.java)

        // A joke app does not get to override a silent phone. Silent means
        // silent; vibrate mode still buzzes, because that is what vibrate means.
        val ringerMode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val mayPlaySound = ringerMode == AudioManager.RINGER_MODE_NORMAL
        val mayVibrate = ringerMode != AudioManager.RINGER_MODE_SILENT

        if (mayPlaySound) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(toneTypeFor(mood))
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                player = MediaPlayer().apply {
                    setDataSource(context, uri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    prepare()
                    start()
                }
            }
        }

        if (mayVibrate) {
            runCatching {
                vibrator = context.getSystemService(Vibrator::class.java)
                val pattern = patternFor(mood)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // repeat = 0: loop the whole pattern until answered.
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
                }
            }
        }
    }

    /** Always safe to call twice — answering and destroying both land here. */
    fun stop() {
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }
}
