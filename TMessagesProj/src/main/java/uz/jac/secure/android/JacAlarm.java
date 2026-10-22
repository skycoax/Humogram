package uz.jac.secure.android;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import org.telegram.messenger.R;

/**
 * The alarm: a scan found a threat ({@link ThreatAlert}), a dangerous file is
 * about to be opened ({@link QuarantineDialogs}), or the link guard stops a
 * link ({@link LinkGuardUi}).
 *
 * <h3>The sounds</h3>
 *
 * Five, chosen on the Humogram page ({@link AlarmSoundActivity}) — radar,
 * klaxon, air-raid siren, "red level" and evacuation — all in
 * {@code res/raw/alarm_*.ogg}, synthesised from scratch by
 * {@code Tools/alarm_sounds.py} (Python + numpy; no recording of any phone
 * maker's alarm, which would be theirs). Each is 3-4 s, -1 dBFS, and loops
 * without a click, so the threat modal can keep it going until the user
 * answers.
 *
 * <h3>Where it plays</h3>
 *
 * Through {@link SoundPool} with {@link AudioAttributes#USAGE_ALARM}: the
 * alarm stream, which has its own volume and is not silenced by the media
 * volume being at zero. A sample that fails to load is answered by a
 * {@link ToneGenerator} burst on the same stream — silence here is a safety
 * regression, not a cosmetic one.
 *
 * <h3>When it stays quiet</h3>
 *
 * "Sound on threat" off ({@link HumogramConfig#isThreatSound}) silences it
 * everywhere; vibration still happens. The phone's silent mode is respected
 * the same way (vibration only), and vibrate mode gets the vibration it asked
 * for: an alert that overrides an explicit "be quiet" spends the product's
 * credibility on one moment of drama. The preview on the settings page plays
 * whatever the switches say, since the user just asked to hear it.
 *
 * <p>Everything is best-effort and safe from any thread: an alarm that cannot
 * sound must never take the warning down with it.
 */
public final class JacAlarm {

    /** The sounds, in the order the settings page lists them. Index 0 is the default. */
    public static final int[] SOUNDS = {
            R.raw.alarm_radar,
            R.raw.alarm_klaxon,
            R.raw.alarm_siren,
            R.raw.alarm_redlevel,
            R.raw.alarm_evacuation,
    };

    /** Their names, same order. */
    public static final int[] NAMES = {
            R.string.jac_alarm_sound_radar,
            R.string.jac_alarm_sound_klaxon,
            R.string.jac_alarm_sound_siren,
            R.string.jac_alarm_sound_redlevel,
            R.string.jac_alarm_sound_evacuation,
    };

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static SoundPool pool;
    /** raw resource -> SoundPool sample id. Guarded by LOCK. */
    private static final SparseIntArray samples = new SparseIntArray();
    private static final SparseBooleanArray ready = new SparseBooleanArray();
    /** raw resource -> loop count to play once its load completes (-2 = nothing waiting). */
    private static final SparseIntArray waiting = new SparseIntArray();

    /** The stream that is playing now, or 0. */
    private static int stream;
    /** The voice clip player (file/link warnings). SoundPool truncates long clips, so voice uses MediaPlayer. */
    private static MediaPlayer voicePlayer;
    private static volatile long lastPlay;
    /** Between {@link #startLoop} and {@link #stop}. A one-pass stream ends on its own and does not count. */
    private static volatile boolean looping;
    private static volatile boolean vibratingInLoop;

    /** One pulse pattern under the alarm; repeated while it loops. */
    private static final long[] VIBRATION = {0, 400, 200, 400, 200, 400, 600};

    private static final Runnable AUTO_STOP = JacAlarm::stop;

    private JacAlarm() {
    }

    /** One pass of the chosen sound, with vibration. Debounced: two calls in 0.7 s alarm once. */
    public static void sound(Context context) {
        if (context == null) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (now - lastPlay < 700) {
            return;
        }
        lastPlay = now;
        try {
            final int ringer = ringerMode(context);
            if (ringer != AudioManager.RINGER_MODE_SILENT) {
                vibrate(context, false);
            }
            if (ringer == AudioManager.RINGER_MODE_NORMAL && HumogramConfig.isThreatSound(context)) {
                play(context, chosen(context), 0);
            }
        } catch (Throwable ignored) {
            // An alarm is an extra, never a dependency.
        }
    }

    /**
     * One pass of a specific voice clip — the spoken Uzbek warnings in the
     * file-open and link dialogs ({@code R.raw.jac_voice_file},
     * {@code R.raw.jac_voice_link}). Each is framed like an airport
     * announcement: the rising four-note chime, the voice, then the same chime
     * falling to say the announcement is over.
     *
     * <p>Always this clip, and always Uzbek: it is an audio file, so the
     * person's chosen interface language does not change it (the owner's call,
     * 2026-10-16 — a spoken warning that reaches someone about to tap works
     * best in the one language everyone here understands). Same rules as
     * {@link #sound}: silent mode vibrates only, and "Sound on threat" mutes
     * the audio while keeping the vibration.
     *
     * <p>Played through {@link MediaPlayer}, not {@link SoundPool}: the clips
     * are ~20 s, and SoundPool silently truncates anything past a second or two
     * (that was why the voice cut off mid-sentence). It first {@link #stop}s
     * whatever is already sounding — a device-scan alarm, say — so the two
     * never play over each other.
     */
    public static void voice(Context context, int rawRes) {
        if (context == null) {
            return;
        }
        try {
            // Kill any ongoing alarm/voice/vibration so this does not overlap it.
            stop();
            lastPlay = SystemClock.elapsedRealtime();
            final int ringer = ringerMode(context);
            if (ringer != AudioManager.RINGER_MODE_SILENT) {
                vibrate(context, false);
            }
            if (ringer == AudioManager.RINGER_MODE_NORMAL && HumogramConfig.isThreatSound(context)) {
                playVoice(context, rawRes);
            }
        } catch (Throwable ignored) {
            // An alarm is an extra, never a dependency.
        }
    }

    /** Play a voice clip to the end on the alarm stream. Released on completion or {@link #stop}. */
    private static void playVoice(Context context, int rawRes) {
        MediaPlayer mp = null;
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            try (AssetFileDescriptor afd = context.getResources().openRawResourceFd(rawRes)) {
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            }
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(p -> {
                synchronized (LOCK) {
                    if (voicePlayer == p) {
                        voicePlayer = null;
                    }
                }
                try {
                    p.release();
                } catch (Throwable ignored) {
                }
            });
            mp.setOnErrorListener((p, what, extra) -> true);
            synchronized (LOCK) {
                voicePlayer = mp;
            }
            mp.prepareAsync();
        } catch (Throwable t) {
            if (mp != null) {
                try {
                    mp.release();
                } catch (Throwable ignored) {
                }
            }
            synchronized (LOCK) {
                voicePlayer = null;
            }
            // Could not play the clip: fall back to the platform tone, never silence.
            tone();
        }
    }

    /**
     * Loop the chosen sound, with vibration, until {@link #stop} or for at
     * most {@code maxMs}. Calling it again while it loops only extends the cap.
     */
    public static void startLoop(Context context, long maxMs) {
        if (context == null) {
            return;
        }
        try {
            MAIN.removeCallbacks(AUTO_STOP);
            MAIN.postDelayed(AUTO_STOP, maxMs);
            if (looping) {
                return;
            }
            looping = true;
            lastPlay = SystemClock.elapsedRealtime();
            final int ringer = ringerMode(context);
            if (ringer != AudioManager.RINGER_MODE_SILENT) {
                vibrate(context, true);
                vibratingInLoop = true;
            }
            if (ringer == AudioManager.RINGER_MODE_NORMAL && HumogramConfig.isThreatSound(context)) {
                play(context, chosen(context), -1);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Settings preview: one pass of sound {@code index}, whatever the switches say. Stops any other. */
    public static void preview(Context context, int index) {
        if (context == null) {
            return;
        }
        stop();
        try {
            play(context, SOUNDS[clamp(index)], 0);
        } catch (Throwable ignored) {
        }
    }

    /** Silence everything: the looping or previewing sound, the voice clip, and the vibration under them. */
    public static void stop() {
        MAIN.removeCallbacks(AUTO_STOP);
        looping = false;
        final MediaPlayer mp;
        synchronized (LOCK) {
            for (int i = 0; i < waiting.size(); i++) {
                waiting.setValueAt(i, -2);
            }
            if (pool != null && stream != 0) {
                try {
                    pool.stop(stream);
                } catch (Throwable ignored) {
                }
            }
            stream = 0;
            mp = voicePlayer;
            voicePlayer = null;
        }
        if (mp != null) {
            try {
                mp.stop();
            } catch (Throwable ignored) {
            }
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
        }
        // Always cancel vibration, not only the looping one: the one-shot
        // pattern under voice()/sound() runs ~2.4 s, and "cancel" must silence
        // it the instant the user answers (that was the buzz that kept going).
        // The cancel goes through the SAME app-context vibrator the loop was
        // started on — a vibration started on one Vibrator instance is not
        // reliably cancelled through another, which is why it kept buzzing
        // after Ignore/Details.
        vibratingInLoop = false;
        try {
            final Vibrator vibrator = appVibrator();
            if (vibrator != null) {
                vibrator.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------

    private static int chosen(Context context) {
        return SOUNDS[clamp(HumogramConfig.getAlarmSound(context))];
    }

    private static int clamp(int index) {
        return index < 0 || index >= SOUNDS.length ? 0 : index;
    }

    private static int ringerMode(Context context) {
        final AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return audio != null ? audio.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;
    }

    /** {@code loop}: 0 = once, -1 = until stopped. */
    private static void play(Context context, int raw, int loop) {
        synchronized (LOCK) {
            if (pool == null) {
                pool = new SoundPool.Builder()
                        .setMaxStreams(1)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .build();
                pool.setOnLoadCompleteListener((p, sampleId, status) -> {
                    synchronized (LOCK) {
                        final int index = samples.indexOfValue(sampleId);
                        if (index < 0) {
                            return;
                        }
                        final int res = samples.keyAt(index);
                        if (status != 0) {
                            // The sample will never arrive; do not stay quiet.
                            samples.delete(res);
                            if (waiting.get(res, -2) != -2) {
                                waiting.delete(res);
                                tone();
                            }
                            return;
                        }
                        ready.put(res, true);
                        final int wanted = waiting.get(res, -2);
                        waiting.delete(res);
                        if (wanted != -2) {
                            stream = p.play(sampleId, 1f, 1f, 1, wanted, 1f);
                        }
                    }
                });
            }
            if (stream != 0) {
                pool.stop(stream);
                stream = 0;
            }
            final int sample = samples.get(raw, 0);
            if (sample == 0) {
                try {
                    samples.put(raw, pool.load(context.getApplicationContext(), raw, 1));
                    waiting.put(raw, loop);
                } catch (Throwable t) {
                    tone();
                }
                return;
            }
            if (!ready.get(raw)) {
                // Still loading: play when it lands.
                waiting.put(raw, loop);
                return;
            }
            stream = pool.play(sample, 1f, 1f, 1, loop, 1f);
            if (stream == 0) {
                tone();
            }
        }
    }

    /** Platform-built alert burst: the fallback that cannot be missing. */
    private static void tone() {
        try {
            final ToneGenerator generator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
            // ToneGenerator holds an AudioTrack; let it go once the tone ends.
            MAIN.postDelayed(generator::release, 1200);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static void vibrate(Context context, boolean repeat) {
        try {
            final Vibrator vibrator = appVibrator();
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            final int repeatAt = repeat ? 0 : -1;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION, repeatAt),
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            } else {
                vibrator.vibrate(VIBRATION, repeatAt);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * One vibrator, from the application context, used for both starting and
     * cancelling — so cancel always reaches the vibration start() began. On
     * Android 12+ that is the {@link VibratorManager}'s default vibrator; the
     * older {@code VIBRATOR_SERVICE} instance can be a different object whose
     * {@code cancel()} does not stop a waveform another instance started.
     */
    @SuppressWarnings("deprecation")
    private static Vibrator appVibrator() {
        try {
            final Context app = org.telegram.messenger.ApplicationLoader.applicationContext;
            if (app == null) {
                return null;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                final VibratorManager vm =
                        (VibratorManager) app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return vm != null ? vm.getDefaultVibrator() : null;
            }
            return (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }
}
