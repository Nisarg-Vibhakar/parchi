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
 * Three sources, in order: the mood's own recording, then a synthesised motif,
 * then a stock system tone. Each step down is a real fallback for the one above
 * failing, not a preference — a call screen that rings silently is just a dialog.
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
     * The actual recording for each mood.
     *
     * A real sound beats a synthesised one every time: a 1950s telephone bell, a
     * doorbell, a toy siren and a dramatic sting say more in half a second than
     * any arpeggio can. Sourced from Wikimedia Commons, public domain, converted
     * to mono Ogg Vorbis and trimmed to loop — 132 KB for all five. See
     * res/raw/CREDITS.txt.
     */
    fun clipFor(mood: Caller.Mood): Int = when (mood) {
        // clapping and cheering: you spent nothing, take the applause
        Caller.Mood.JOY -> dev.nisarg.paisa.R.raw.ring_joy
        // a 1950s Model 500 telephone bell
        Caller.Mood.CALM -> dev.nisarg.paisa.R.raw.ring_calm
        // an old mechanical doorbell: someone is at the door and will not leave
        Caller.Mood.CONCERN -> dev.nisarg.paisa.R.raw.ring_concern
        // a toy siren, which is funnier and more annoying than a real one
        Caller.Mood.ALARM -> dev.nisarg.paisa.R.raw.ring_alarm
        // the three-chord dramatic sting. dun. dun. DUN.
        Caller.Mood.DOOM -> dev.nisarg.paisa.R.raw.ring_doom
    }

    /**
     * Kept only for the case where a clip will not open. Synthesis needs no
     * files and cannot fail on a codec.
     */
    fun toneTypeFor(mood: Caller.Mood): Int = when (mood) {
        Caller.Mood.JOY, Caller.Mood.CALM -> RingtoneManager.TYPE_NOTIFICATION
        Caller.Mood.CONCERN -> RingtoneManager.TYPE_RINGTONE
        Caller.Mood.ALARM, Caller.Mood.DOOM -> RingtoneManager.TYPE_ALARM
    }

    private var player: MediaPlayer? = null
    private var track: android.media.AudioTrack? = null
    private var vibrator: Vibrator? = null

    fun start(context: Context, mood: Caller.Mood) {
        val audio = context.getSystemService(AudioManager::class.java)

        // A joke app does not get to override a silent phone. Silent means
        // silent; vibrate mode still buzzes, because that is what vibrate means.
        val ringerMode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val mayPlaySound = ringerMode == AudioManager.RINGER_MODE_NORMAL
        val mayVibrate = ringerMode != AudioManager.RINGER_MODE_SILENT

        if (mayPlaySound) {
            // The recording first, then synthesis, then a stock tone. Each step
            // down is a real fallback, not a preference.
            var played = runCatching { playClip(context, mood) }.getOrDefault(false)
            if (!played) played = runCatching { playMotif(mood) }.getOrDefault(false)
            if (!played) runCatching {
                val uri = RingtoneManager.getDefaultUri(toneTypeFor(mood))
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                player = MediaPlayer().apply {
                    setAudioAttributes(ringtoneAttributes())   // before prepare, always
                    setDataSource(context, uri)
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

    private fun ringtoneAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Plays the mood's recording on a loop.
     *
     * Built by hand rather than with MediaPlayer.create(), which prepares the
     * player internally — so attributes set afterwards are silently ignored and
     * the stream stays USAGE_UNKNOWN. Android's audio hardening then mutes it as
     * background media playback, and the ringtone plays into nothing. The
     * attributes MUST be set before prepare().
     */
    private fun playClip(context: Context, mood: Caller.Mood): Boolean {
        val afd = context.resources.openRawResourceFd(clipFor(mood)) ?: return false
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(ringtoneAttributes())
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            mp.isLooping = true
            mp.prepare()
            mp.setVolume(1f, 1f)
            mp.start()
            player = mp
            true
        } finally {
            runCatching { afd.close() }
        }
    }

    /**
     * Plays the mood's motif on a loop, straight from generated PCM.
     * MODE_STATIC with loop points means the whole ring sits in the audio buffer
     * and repeats in hardware — no thread feeding it, nothing to stall.
     */
    private fun playMotif(mood: Caller.Mood): Boolean {
        val pcm = Tones.render(mood)
        if (pcm.isEmpty()) return false
        val bytes = pcm.size * 2

        val at = android.media.AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Tones.SAMPLE_RATE)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .build()

        at.write(pcm, 0, pcm.size)
        at.setLoopPoints(0, pcm.size, -1)   // -1: loop until stopped
        at.play()
        track = at
        return true
    }

    /** Always safe to call twice — answering and destroying both land here. */
    fun stop() {
        runCatching { track?.stop(); track?.release() }
        track = null
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }
}
