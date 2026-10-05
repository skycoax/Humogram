package uz.jac.secure.android;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The device check — what state is this phone in? — and the protection
 * percentage it shares with the virus scanner.
 *
 * <p>A messenger that scans files and links answers "is this thing I was sent
 * dangerous?". This class answers the question one level down — "is the
 * ground it all runs on solid?" — because a scanner on a rooted phone with no
 * screen lock is a seatbelt in a car with no brakes.
 *
 * <h3>What is checked, and what deliberately is not</h3>
 *
 * The device checks read local state and nothing else: a handful of
 * file-exists probes, two Settings values, one system service, one build
 * property. The one account-side check — does the account have a cloud
 * password — is the same {@code account.getPassword} status request Telegram's
 * own Privacy settings page makes, to Telegram and nobody else. Nothing about
 * any of it is sent anywhere.
 *
 * <p>Play Protect status and system-wide sideloading are not checked because
 * Android gives an ordinary app no honest way to read them; a check that
 * guesses would teach the user to distrust the ones that don't.
 *
 * <h3>The percentage</h3>
 *
 * {@link #score} weighs the virus scan's verdict and each check
 * ({@link #weight}); a check whose answer is not known yet (the cloud password
 * before Telegram has answered, the scan before it has run) counts neither
 * way. Any threat the scanner found caps the result at {@link #THREAT_CAP}: a
 * phone with malware on it is not "70% protected" because its screen lock is
 * set. The Settings card and the Humogram page show the same number from the
 * same call.
 */
public final class SecurityCheckup {

    public static final int OK = 0;
    /** Widens the attack surface; most people have no reason to have it on. */
    public static final int WARN = 1;
    /** Someone with the phone in their hand, or a malicious app, has a straight path. */
    public static final int DANGER = 2;

    /** Stable ids; the page maps them to titles, advice and fix actions. */
    public static final int CHECK_SCREEN_LOCK = 1;
    public static final int CHECK_ROOT = 2;
    public static final int CHECK_ADB = 3;
    public static final int CHECK_PATCH = 4;
    public static final int CHECK_2FA = 5;

    /** The virus scan's share of the percentage. */
    private static final int WEIGHT_SCAN = 40;
    /** Highest percentage a phone can show while the scanner reports a threat. */
    private static final int THREAT_CAP = 30;

    /** At or above: green. */
    public static final int LEVEL_GOOD = 80;
    /** At or above (and below good): orange; under it, red. */
    public static final int LEVEL_FAIR = 50;

    /** How stale a security-patch level may be before it is worth a warning. */
    private static final long PATCH_STALE_MS = 365L * 24 * 60 * 60 * 1000;
    /** An automatic cloud-password re-check is skipped within this of the last one. */
    private static final long PASSWORD_RECHECK_MS = 60_000L;

    public static final class Finding {
        public final int id;
        public final int severity;

        Finding(int id, int severity) {
            this.id = id;
            this.severity = severity;
        }
    }

    // Cloud password: UI thread only.
    private static final TL_account.Password[] passwords = new TL_account.Password[UserConfig.MAX_ACCOUNT_COUNT];
    private static final int[] passwordRequests = new int[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] passwordAskedAt = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final boolean[] passwordFailed = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static final ArrayList<Runnable> listeners = new ArrayList<>();

    private SecurityCheckup() {
    }

    // ------------------------------------------------------------------
    // Device checks
    // ------------------------------------------------------------------

    /** Every local check. Cheap enough for the UI thread: a few file stats and settings reads. */
    public static List<Finding> runLocal(Context context) {
        final List<Finding> findings = new ArrayList<>(4);
        findings.add(new Finding(CHECK_SCREEN_LOCK, hasScreenLock(context) ? OK : DANGER));
        findings.add(new Finding(CHECK_ROOT, looksRooted() ? DANGER : OK));
        findings.add(new Finding(CHECK_ADB, isAdbEnabled(context) ? WARN : OK));
        findings.add(new Finding(CHECK_PATCH, isPatchStale() ? WARN : OK));
        return findings;
    }

    /** The settings screen that fixes a finding, or null when only advice helps. */
    static String settingsActionFor(int id) {
        switch (id) {
            case CHECK_SCREEN_LOCK:
                return Settings.ACTION_SECURITY_SETTINGS;
            case CHECK_ADB:
                return Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS;
            case CHECK_PATCH:
                return "android.settings.SYSTEM_UPDATE_SETTINGS";
            default:
                return null; // root: there is no settings page out of root
        }
    }

    private static boolean hasScreenLock(Context context) {
        try {
            final KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null) {
                return true; // unknowable — do not accuse
            }
            // isDeviceSecure arrived in API 23; below that isKeyguardSecure is
            // the closest answer (it also counts a SIM lock, which overreports
            // slightly — the right way to be wrong for a warning like this).
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? km.isDeviceSecure()
                    : km.isKeyguardSecure();
        } catch (Throwable ignored) {
            return true; // unknowable — do not accuse
        }
    }

    private static boolean looksRooted() {
        try {
            final String tags = Build.TAGS;
            if (tags != null && tags.contains("test-keys")) {
                return true;
            }
            final String[] paths = {
                    "/system/bin/su", "/system/xbin/su", "/sbin/su",
                    "/su/bin/su", "/system/app/Superuser.apk",
                    "/system/sd/xbin/su", "/data/local/xbin/su", "/data/local/bin/su"
            };
            for (String path : paths) {
                if (new File(path).exists()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isAdbEnabled(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPatchStale() {
        try {
            final String patch = Build.VERSION.SECURITY_PATCH;
            if (patch == null || patch.isEmpty()) {
                return false; // unknowable — do not accuse
            }
            final Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch);
            return date != null && System.currentTimeMillis() - date.getTime() > PATCH_STALE_MS;
        } catch (ParseException | RuntimeException ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Cloud password
    // ------------------------------------------------------------------

    /** The account's password state as Telegram last told us, or null before it has. */
    public static TL_account.Password getPassword(int account) {
        return valid(account) ? passwords[account] : null;
    }

    /** True when the last request failed and nothing is known; the row says "couldn't check". */
    public static boolean isPasswordUnknown(int account) {
        return valid(account) && passwords[account] == null && passwordFailed[account];
    }

    /**
     * Ask Telegram whether the account has a cloud password. One request per
     * account at a time; an automatic re-check ({@code force} false) is skipped
     * within {@link #PASSWORD_RECHECK_MS} of the last answer. Listeners hear
     * about the answer on the UI thread.
     */
    public static void requestPassword(int account, boolean force) {
        if (!valid(account) || passwordRequests[account] != 0) {
            return;
        }
        try {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            if (!force && passwords[account] != null && now - passwordAskedAt[account] < PASSWORD_RECHECK_MS) {
                return;
            }
            final ConnectionsManager connections = ConnectionsManager.getInstance(account);
            passwordRequests[account] = connections.sendRequest(new TL_account.getPassword(),
                    (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                        passwordRequests[account] = 0;
                        passwordAskedAt[account] = SystemClock.elapsedRealtime();
                        if (response instanceof TL_account.Password) {
                            passwords[account] = (TL_account.Password) response;
                            passwordFailed[account] = false;
                        } else if (passwords[account] == null) {
                            passwordFailed[account] = true;
                        }
                        notifyListeners();
                    }), ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin);
            if (passwordRequests[account] == 0) {
                // sendRequest hands out ids from 1; 0 would read as "idle".
                passwordRequests[account] = -1;
            }
            passwordFailed[account] = false;
        } catch (Throwable t) {
            passwordRequests[account] = 0;
            FileLog.e(t);
        }
    }

    /** UI thread. Called after every cloud-password answer. */
    public static void addListener(Runnable listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            try {
                listener.run();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    private static boolean valid(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
    }

    // ------------------------------------------------------------------
    // The percentage
    // ------------------------------------------------------------------

    static int weight(int id) {
        switch (id) {
            case CHECK_SCREEN_LOCK:
            case CHECK_ROOT:
            case CHECK_2FA:
                return 15;
            case CHECK_ADB:
                return 10;
            case CHECK_PATCH:
                return 5;
            default:
                return 0;
        }
    }

    /**
     * How protected this phone and account are, 0–100, or -1 when nothing is
     * known yet. Runs the local checks, so UI thread is fine but a draw loop
     * is not.
     */
    public static int score(Context context, DeviceScanReport report, int account) {
        if (context == null) {
            return -1;
        }
        int earned = 0;
        int possible = 0;
        boolean threats = false;
        if (report != null && !DeviceScanUi.isEmptyFailure(report)) {
            possible += WEIGHT_SCAN;
            int count = 0;
            try {
                count = report.threatCount(context);
            } catch (Throwable t) {
                FileLog.e(t);
            }
            if (count > 0) {
                threats = true;
            } else {
                earned += WEIGHT_SCAN;
            }
        }
        for (Finding finding : runLocal(context)) {
            possible += weight(finding.id);
            if (finding.severity == OK) {
                earned += weight(finding.id);
            }
        }
        final TL_account.Password password = getPassword(account);
        if (password != null) {
            possible += weight(CHECK_2FA);
            if (password.has_password) {
                earned += weight(CHECK_2FA);
            }
        }
        if (possible == 0) {
            return -1;
        }
        int percent = Math.round(100f * earned / possible);
        if (threats) {
            percent = Math.min(percent, THREAT_CAP);
        }
        return percent;
    }

    /** The colour a percentage is drawn in: green, orange or red. */
    public static int levelColor(int score, Theme.ResourcesProvider resourcesProvider) {
        if (score >= LEVEL_GOOD) {
            return Theme.getColor(Theme.key_color_green, resourcesProvider);
        }
        if (score >= LEVEL_FAIR) {
            return Theme.getColor(Theme.key_color_orange, resourcesProvider);
        }
        return Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
    }
}
