package uz.jac.secure.android;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.os.Vibrator;

import org.telegram.messenger.R;

/**
 * The siren that plays when a dangerous file is about to be opened, or a link
 * to a site on the block list ({@link LinkGuardUi}).
 *
 * <h3>What this is for</h3>
 *
 * One sound, one meaning: "you are about to do the thing this product exists
 * to stop". It fires when a warning dialog goes up in front of a dangerous or
 * unchecked installer — not for verdicts, not for downloads, not for anything
 * the user is merely looking at. A sound that plays often is a sound people
 * learn to ignore, and the population this app is built for is exactly the one
 * that taps through visual warnings; the siren is the channel that still works
 * when the eyes have already decided.
 *
 * <h3>The asset and its fallback</h3>
 *
 * {@code res/raw/jac_alarm.wav} is a classic alarm-clock pattern — two groups
 * of four quick beeps — synthesised from scratch for Humogram (no sample of any
 * phone maker's ringtone is in it). Unambiguous as an alert and unlike every
 * sound Telegram itself makes, so it cannot be mistaken for a message arriving. It is played through
 * {@link SoundPool} on {@link AudioManager#STREAM_ALARM}: the stream the
 * platform reserves for "needs attention now", which is not silenced by the
 * media volume being at zero.
 *
 * <p>A missing or undecodable asset must not mean silence — silence here is a
 * safety regression, not a cosmetic one. If the sample fails to load, the
 * fallback is a {@link ToneGenerator} burst on the same stream: built into the
 * platform, no asset to lose, works since API 1.
 *
 * <h3>Silent mode</h3>
 *
 * {@code RINGER_MODE_SILENT} is respected for the sound and answered with
 * vibration instead; {@code RINGER_MODE_VIBRATE} gets both suppressed audio
 * and the vibration it asked for. A security alert that overrides an explicit
 * "be quiet" from the user would spend the product's credibility on one
 * moment of drama — and the dialog with its countdown is still in the way
 * either way.
 *
 * <h3>Lifetime</h3>
 *
 * The pool is created lazily and kept for the life of the process, the same
 * way {@code NotificationsController} keeps its message-sound pool: one
 * decoded 46 KB sample is cheaper than a create/release race on every dialog.
 * Everything is wrapped and best-effort — an alarm that cannot sound must
 * never take the warning dialog down with it.
 */
public final class JacAlarm {

    private static final Object LOCK = new Object();
    private static SoundPool pool;
    private static int soundId;
    private static boolean loadRequested;
    private static volatile boolean loaded;
    private static volatile long lastPlay;

    /** Vibration pattern mirroring the audio: four quick pulses, like the beeps. */
    private static final long[] VIBRATION = {0, 90, 60, 90, 60, 90, 60, 90};

    private JacAlarm() {
    }

    /**
     * Sound the siren, best-effort. Safe to call from any thread; debounced so
     * a dialog that is shown twice in quick succession alarms once.
     */
    public static void sound(Context context) {
        if (context == null) {
            return;
        }
        try {
            long now = SystemClock.elapsedRealtime();
            if (now - lastPlay < 700) {
                return;
            }
            lastPlay = now;

            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int ringerMode = audio != null ? audio.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;

            if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                vibrate(context);
            }
            if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                play(context);
            }
        } catch (Throwable ignored) {
            // An alarm is an extra, never a dependency.
        }
    }

    private static void play(Context context) {
        synchronized (LOCK) {
            if (pool == null) {
                pool = new SoundPool(1, AudioManager.STREAM_ALARM, 0);
                pool.setOnLoadCompleteListener((p, sampleId, status) -> {
                    if (status == 0) {
                        loaded = true;
                        try {
                            p.play(sampleId, 1f, 1f, 1, 0, 1f);
                        } catch (Throwable ignored) {
                        }
                    } else {
                        // The sample will never arrive; do not stay quiet.
                        tone();
                    }
                });
            }
            if (!loadRequested) {
                loadRequested = true;
                try {
                    soundId = pool.load(context.getApplicationContext(), R.raw.jac_alarm, 1);
                } catch (Throwable t) {
                    tone();
                }
                // First call: the load-complete listener plays it.
                return;
            }
        }
        if (loaded && soundId != 0) {
            try {
                pool.play(soundId, 1f, 1f, 1, 0, 1f);
            } catch (Throwable ignored) {
                tone();
            }
        } else if (!loaded) {
            // Load still in flight or failed silently; the tone is immediate.
            tone();
        }
    }

    /** Platform-built alert burst: the fallback that cannot be missing. */
    private static void tone() {
        try {
            ToneGenerator generator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
            // ToneGenerator holds an AudioTrack; give the tone time to finish,
            // then let it go.
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(generator::release, 1200);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static void vibrate(Context context) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VIBRATION, -1);
            }
        } catch (Throwable ignored) {
        }
    }
}
