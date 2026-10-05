package uz.jac.secure.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.R;

/**
 * The handful of choices that make this app feel like the user's, kept in one
 * small store.
 *
 * <p>Right now that is one setting: which ornament the chat list wears. It is a
 * cosmetic, per-device preference — not account data — so it lives in a plain
 * {@link SharedPreferences} file rather than anywhere that syncs. A cached
 * value keeps the read off the draw path, which asks for it every frame.
 *
 * <p>The device virus scanner's choices live here too — whether it runs at
 * launch, the answer to its launch notice, and which apps the user trusts —
 * for the same reason: they describe this phone, not the Telegram account.
 */
public final class HumogramConfig {

    private static final String PREFS = "jac_humogram";
    private static final String KEY_ORNAMENT = "ornament";
    private static final String KEY_LINK_GUARD = "link_guard";

    /**
     * The @username of the recommended cyber-awareness channel, without the {@code @}.
     *
     * <p>Were it ever emptied again, every surface that shows it checks for
     * emptiness and hides itself, so no dead link can ship.
     */
    public static final String SECURITY_CHANNEL = "jizzax_kiber";

    private static final String KEY_CHANNEL_HINT_DONE = "channel_hint_done";

    /**
     * Whether the chat list should still be suggesting the security channel.
     *
     * <p>Once: the hint disappears forever after one tap or one dismissal.
     * A recommendation that keeps coming back is an advertisement, and this
     * fork's standing with its users rests on never advertising at them.
     */
    public static boolean shouldShowChannelHint(Context context) {
        if (SECURITY_CHANNEL.isEmpty()) {
            return false;
        }
        try {
            return !prefs(context).getBoolean(KEY_CHANNEL_HINT_DONE, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setChannelHintDismissed(Context context) {
        try {
            prefs(context).edit().putBoolean(KEY_CHANNEL_HINT_DONE, true).apply();
        } catch (Throwable ignored) {
        }
    }

    /** The suzani border this shipped with — the default. */
    public static final int ORNAMENT_SUZANI = 0;
    /** An eight-point-star geometric lattice (khatam / mashrabiya). */
    public static final int ORNAMENT_GEOMETRY = 1;
    /** The crescent-and-stars of the Uzbek flag, as a repeating motif. */
    public static final int ORNAMENT_FLAG = 2;
    /** No ornament at all — a plain background. */
    public static final int ORNAMENT_OFF = 3;

    private static volatile int cachedOrnament = -1;

    private HumogramConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Set once {@link #cleanUpRemovedFeatures} has run on this install. */
    private static final String KEY_CLEANUP_V1 = "cleanup_removed_features_v1";

    /**
     * Erase what features this build no longer has left on an upgraded phone:
     * the panic-passcode prefs file (a salted hash of that code), the old
     * backend device token, and the settings of link-tracker cleaning, the
     * device checkup and the scanner's cloud mode. Nothing reads any of it
     * now, and a hash of a passcode is not something to keep "just because".
     * Runs once; worker thread only (it touches files).
     */
    static void cleanUpRemovedFeatures(Context context) {
        try {
            final SharedPreferences p = prefs(context);
            if (p.getBoolean(KEY_CLEANUP_V1, false)) {
                return;
            }
            final SharedPreferences.Editor editor = p.edit();
            for (String key : new java.util.ArrayList<>(p.getAll().keySet())) {
                if ("link_hygiene".equals(key) || "devscan_mode".equals(key)
                        || key.startsWith("checkup_") || key.startsWith("devscan_net")) {
                    editor.remove(key);
                }
            }
            editor.putBoolean(KEY_CLEANUP_V1, true).apply();

            final Context app = context.getApplicationContext();
            for (String name : new String[]{"jac_duress", "jac_secure"}) {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    app.deleteSharedPreferences(name);
                }
            }
        } catch (Throwable ignored) {
            // Leftovers are harmless; never let this break a start-up.
        }
    }

    public static int getOrnament(Context context) {
        int c = cachedOrnament;
        if (c >= 0) {
            return c;
        }
        int value = ORNAMENT_SUZANI;
        try {
            value = prefs(context).getInt(KEY_ORNAMENT, ORNAMENT_SUZANI);
        } catch (Throwable ignored) {
        }
        cachedOrnament = value;
        return value;
    }

    public static void setOrnament(Context context, int value) {
        cachedOrnament = value;
        try {
            prefs(context).edit().putInt(KEY_ORNAMENT, value).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whether the link guard is on: links to sites that are not on the trusted
     * list are drawn red and take two confirmations to open.
     *
     * <p>On by default, and a read that fails reads as on. This is the one
     * protection that works by refusing to assume a site is fine, so a broken
     * preference store must not be what quietly turns it off.
     *
     * <p>Cached like the ornament, and with more reason: {@link LinkGuard#tierOf}
     * asks from inside a text span's draw pass, once per link per frame and
     * not always on the UI thread, where a preferences lookup has no business
     * running. {@link LinkGuard} warms the cache at start-up so the first
     * frame does not pay for the read either.
     */
    private static volatile int cachedLinkGuard = -1;

    public static boolean isLinkGuard(Context context) {
        int c = cachedLinkGuard;
        if (c >= 0) {
            return c == 1;
        }
        boolean value = true;
        try {
            value = prefs(context).getBoolean(KEY_LINK_GUARD, true);
        } catch (Throwable ignored) {
        }
        cachedLinkGuard = value ? 1 : 0;
        return value;
    }

    public static void setLinkGuard(Context context, boolean value) {
        cachedLinkGuard = value ? 1 : 0;
        try {
            prefs(context).edit().putBoolean(KEY_LINK_GUARD, value).apply();
        } catch (Throwable ignored) {
        }
        // Every link on screen was coloured under the old answer. The guard
        // drops what it remembered, bumps its generation and redraws the
        // chats that are open. After the write, so a listener that reads the
        // setting back sees the new value. Guarded, because this is a
        // settings switch: if the guard's class cannot even load, the switch
        // must still flip.
        try {
            LinkGuard.onToggleChanged(context, value);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Device virus scanner (DeviceScanner, DeviceScanSection, DeviceScanLaunch)
    //
    // Everything here is a per-device choice about what this phone's app list
    // may be used for, so it lives in this local file and never in anything
    // that syncs with the Telegram account: a consent given on one phone is
    // not a consent for the next one. The scanner runs on the phone only, so
    // there is no engine to choose and nothing to consent to sending.
    // ---------------------------------------------------------------------

    private static final String KEY_DEVSCAN_ON_LAUNCH = "devscan_on_launch";
    private static final String KEY_DEVSCAN_CONSENT = "devscan_consent";
    private static final String KEY_DEVSCAN_TRUSTED = "devscan_trusted";
    private static final String KEY_DEVSCAN_NOTIFIED = "devscan_notified";
    private static final String KEY_DEVSCAN_FIRST_CLEAN = "devscan_first_clean_shown";

    /**
     * Upper bound on remembered "already told the user" keys.
     *
     * Every app update mints a new trust key, so the set only ever grows; past
     * this many the oldest entries are dropped until it fits. Dropping the
     * oldest rather than clearing the lot is the point: clearing meant that
     * every Bulletin the user had already seen came back at once.
     */
    private static final int MAX_NOTIFIED_KEYS = 512;

    /**
     * Whether the installed-apps check runs every time the chat list first
     * appears. On by default; the one-time launch notice
     * ({@link #getDeviceScanConsent}) is what actually gates the first run.
     */
    public static boolean isDeviceScanOnLaunch(Context context) {
        try {
            return prefs(context).getBoolean(KEY_DEVSCAN_ON_LAUNCH, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setDeviceScanOnLaunch(Context context, boolean on) {
        try {
            prefs(context).edit().putBoolean(KEY_DEVSCAN_ON_LAUNCH, on).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * The answer to the one-time launch notice: -1 never asked, 0 declined,
     * 1 accepted.
     *
     * <p>The launch notice is about checking the app list on the phone at all:
     * Play treats the app inventory as personal data however it is obtained,
     * even when nothing leaves the phone.
     */
    public static int getDeviceScanConsent(Context context) {
        try {
            int value = prefs(context).getInt(KEY_DEVSCAN_CONSENT, -1);
            return value == 0 || value == 1 ? value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static void setDeviceScanConsent(Context context, int value) {
        try {
            prefs(context).edit().putInt(KEY_DEVSCAN_CONSENT, value == 0 || value == 1 ? value : -1).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whether the user marked this exact build of an app as trusted.
     *
     * <p>The key is {@code packageName + "@" + versionCode}
     * ({@code DeviceScanReport.AppResult#trustKey()}), so an update resets
     * trust: the thing the user vouched for was one specific APK, and a
     * banker that ships as a harmless-looking first version and turns hostile
     * in an update is exactly the case trust must not carry over.
     */
    public static boolean isAppTrusted(Context context, String trustKey) {
        if (trustKey == null) {
            return false;
        }
        try {
            java.util.Set<String> trusted = prefs(context).getStringSet(KEY_DEVSCAN_TRUSTED, null);
            return trusted != null && trusted.contains(trustKey);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static synchronized void setAppTrusted(Context context, String trustKey, boolean trusted) {
        if (trustKey == null) {
            return;
        }
        try {
            SharedPreferences prefs = prefs(context);
            // getStringSet's result must not be modified in place (the
            // framework hands back its own instance), so edit a copy.
            java.util.Set<String> current = prefs.getStringSet(KEY_DEVSCAN_TRUSTED, null);
            java.util.Set<String> next = current != null
                    ? new java.util.HashSet<>(current) : new java.util.HashSet<String>();
            boolean changed = trusted ? next.add(trustKey) : next.remove(trustKey);
            if (changed) {
                prefs.edit().putStringSet(KEY_DEVSCAN_TRUSTED, next).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Mark a once-only notice as shown; true only the first time a key is marked.
     *
     * <p>For the SUSPICIOUS-app Bulletin after a launch scan: one nudge per
     * trust key, not one per launch. Synchronized because check-then-add on a
     * preference set is otherwise a race between two callers that would both
     * see "not yet" and both show the Bulletin.
     *
     * <p>Each entry is stored as {@code shownAtMillis + "|" + key} so the set
     * can age out instead of being emptied when it fills up. Timestamp first, and
     * read only when the part before the first {@code |} is all digits, because
     * the keys themselves contain {@code |} (a file key is
     * {@code "file:" + path + "|" + size}); an entry written before this format
     * existed therefore parses as itself with an unknown time, and is among the
     * first to be dropped.
     */
    public static synchronized boolean markThreatNotified(Context context, String key) {
        if (key == null) {
            return false;
        }
        try {
            SharedPreferences prefs = prefs(context);
            java.util.Set<String> current = prefs.getStringSet(KEY_DEVSCAN_NOTIFIED, null);
            java.util.List<String> kept = new java.util.ArrayList<>();
            if (current != null) {
                for (String entry : current) {
                    if (key.equals(notifiedKeyOf(entry))) {
                        return false;
                    }
                    kept.add(entry);
                }
            }
            if (kept.size() >= MAX_NOTIFIED_KEYS) {
                // Oldest first, then drop from the front until the new one fits.
                java.util.Collections.sort(kept, new java.util.Comparator<String>() {
                    @Override
                    public int compare(String a, String b) {
                        long ta = notifiedTimeOf(a);
                        long tb = notifiedTimeOf(b);
                        return ta < tb ? -1 : (ta > tb ? 1 : 0);
                    }
                });
                kept.subList(0, kept.size() - MAX_NOTIFIED_KEYS + 1).clear();
            }
            kept.add(System.currentTimeMillis() + "|" + key);
            prefs.edit().putStringSet(KEY_DEVSCAN_NOTIFIED, new java.util.HashSet<>(kept)).apply();
            return true;
        } catch (Throwable ignored) {
            // Fail quiet: a broken preference store must not turn into a
            // Bulletin at every launch.
            return false;
        }
    }

    /** The key part of a stored notified entry; the whole string for a legacy one. */
    private static String notifiedKeyOf(String entry) {
        int bar = entry.indexOf('|');
        return bar > 0 && isDigits(entry, 0, bar) ? entry.substring(bar + 1) : entry;
    }

    /** When the entry was shown, or 0 for a legacy entry that does not say. */
    private static long notifiedTimeOf(String entry) {
        int bar = entry.indexOf('|');
        if (bar <= 0 || !isDigits(entry, 0, bar)) {
            return 0L;
        }
        try {
            return Long.parseLong(entry.substring(0, bar));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean isDigits(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the "no threats found" Bulletin has been shown after a first ever
     * device scan.
     *
     * <p>Its own flag rather than a key in the notified set: that set is capped
     * and ages out, and a one-time flag that can be evicted is not a one-time
     * flag — an eviction used to bring back both this Bulletin and every
     * suspicious-app one the user had already seen.
     */
    public static boolean isDeviceScanFirstCleanShown(Context context) {
        try {
            return prefs(context).getBoolean(KEY_DEVSCAN_FIRST_CLEAN, false);
        } catch (Throwable ignored) {
            // Fail quiet toward "already shown": a broken store must not turn
            // into a Bulletin at every launch.
            return true;
        }
    }

    public static void setDeviceScanFirstCleanShown(Context context) {
        try {
            prefs(context).edit().putBoolean(KEY_DEVSCAN_FIRST_CLEAN, true).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * The tile drawable for the chosen ornament, or 0 for none.
     *
     * All tiles are white silhouettes with graded alpha, so the same tinting
     * path in {@link HumoOrnament} works for every one — the choice here is
     * geometry, not colour.
     */
    public static int ornamentTileRes(int ornament) {
        switch (ornament) {
            case ORNAMENT_GEOMETRY:
                return R.drawable.humo_geo;
            case ORNAMENT_FLAG:
                return R.drawable.humo_flag;
            case ORNAMENT_OFF:
                return 0;
            case ORNAMENT_SUZANI:
            default:
                return R.drawable.humo_ornament;
        }
    }
}
