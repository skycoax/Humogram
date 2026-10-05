package uz.jac.secure.android;

import android.app.Activity;
import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The device virus scan that runs every time Humogram starts, and the one
 * thing it says afterwards.
 *
 * <pre>DeviceScanLaunch.maybeRun(this);   // DialogsActivity.onResume</pre>
 *
 * <h3>When it runs</h3>
 *
 * Once per process, on the scanner's own worker thread, about a second and a
 * half after the chat list appears — never as a service, alarm or job. The
 * hook is the chat list's {@code onResume}, which fires for many reasons (a
 * theme change rebuilds every fragment; unlocking the passcode resumes the list
 * again), so a static flag makes the scan happen once. The flag is consumed
 * only when a scan (or the one-time notice) actually starts:
 *
 * <ul>
 *   <li>At cold start the list resumes <em>before</em> LaunchActivity puts
 *       the passcode screen up. After the delay the lock is visible in
 *       SharedConfig, so the check returns without spending the flag, and the
 *       resume that follows a correct passcode runs it for real. Nothing about
 *       the phone is read while the app is locked.</li>
 *   <li>No account signed in, the scanner failed to start, or launch scans
 *       switched off — nothing happens.</li>
 * </ul>
 *
 * <h3>The one-time notice</h3>
 *
 * Reading which apps are installed is personal data under Play's rules even
 * when nothing leaves the phone, and a scan that starts by itself should not be
 * a surprise. So before the first launch scan the user is told what it does
 * and asked: "Turn on" scans now and on every launch; "Not now" switches
 * launch scans off (the page can switch them back on); tapping outside decides
 * nothing and the question comes back next launch.
 *
 * <h3>What it says afterwards</h3>
 *
 * Proportionate, because a launch-time alarm that fires for nothing teaches
 * people to dismiss it:
 * <ul>
 *   <li>An untrusted app (or APK file) rated dangerous or malicious — a
 *       Bulletin with upstream's error animation and "Review". Every launch,
 *       for as long as it is there and not trusted: that is the one case worth
 *       repeating. Not a dialog: nothing about it needs an answer before the
 *       chat list may be used.</li>
 *   <li>Only suspicious ones — one Bulletin, once per app version.</li>
 *   <li>Nothing found — a quiet Bulletin after the very first scan, and later
 *       only when new or updated apps were checked.</li>
 * </ul>
 * Before showing anything the chat list must still be in front: if the user
 * has moved on, the result waits and is shown the next time the list
 * resumes in this process.
 */
public final class DeviceScanLaunch {

    /** onResume at cold start is before the first frame: wait for the list to be drawn. */
    private static final long DELAY_MS = 1500;

    private static final AtomicBoolean started = new AtomicBoolean();
    /** A finished launch scan whose outcome has not been shown yet. UI thread only. */
    private static DeviceScanReport pendingOutcome;

    private DeviceScanLaunch() {
    }

    public static void maybeRun(BaseFragment fragment) {
        try {
            if (fragment == null || fragment.getContext() == null || fragment.isInPreviewMode()) {
                return;
            }
            if (started.get() && pendingOutcome == null) {
                return;
            }
            // Several resumes can queue several checks; each one re-checks
            // the flag, so at most one of them starts anything.
            AndroidUtilities.runOnUIThread(() -> check(fragment), DELAY_MS);
        } catch (Throwable t) {
            // The chat list must never be the thing that breaks.
            FileLog.e(t);
        }
    }

    private static void check(BaseFragment fragment) {
        try {
            if (!isInFront(fragment) || isLocked()) {
                // Not consumed: the resume after unlock (or after the user
                // comes back to the list) asks again.
                return;
            }
            if (started.get()) {
                showPendingOutcome(fragment);
                return;
            }
            final Activity activity = fragment.getParentActivity();
            if (!UserConfig.getInstance(fragment.getCurrentAccount()).isClientActivated()
                    || !ScannerBootstrap.isInstalled()
                    || !HumogramConfig.isDeviceScanOnLaunch(activity)) {
                return;
            }
            final int consent = HumogramConfig.getDeviceScanConsent(activity);
            if (consent == 0) {
                return;
            }
            if (consent < 0) {
                showNotice(fragment);
                return;
            }
            if (started.compareAndSet(false, true)) {
                startScan(fragment);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** The passcode is up, or about to be: SharedConfig says so before the screen does. */
    private static boolean isLocked() {
        final String hash = SharedConfig.passcodeHash;
        return SharedConfig.isWaitingForPasscodeEnter
                || (hash != null && hash.length() > 0 && SharedConfig.appLocked);
    }

    private static boolean isInFront(BaseFragment fragment) {
        return fragment != null && fragment.getParentActivity() != null && !fragment.isPaused();
    }

    // ------------------------------------------------------------------
    // The notice
    // ------------------------------------------------------------------

    private static void showNotice(BaseFragment fragment) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        final Context app = activity.getApplicationContext();
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTopImage(new DeviceScanGlyphDrawable(JacIcons.Glyph.SHIELD, 52,
                        Theme.key_featuredStickers_buttonText), Theme.getColor(Theme.key_dialogTopBackground))
                .setTitle(JacStrings.get(activity, R.string.jac_devscan_notice_title))
                .setMessage(JacStrings.get(activity, R.string.jac_devscan_notice_body))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_devscan_notice_on), (d, which) -> {
                    if (!UserConfig.getInstance(fragment.getCurrentAccount()).isClientActivated()) {
                        // Logged out underneath the notice: nothing is agreed to.
                        started.set(false);
                        return;
                    }
                    HumogramConfig.setDeviceScanConsent(app, 1);
                    HumogramConfig.setDeviceScanOnLaunch(app, true);
                    startScan(fragment);
                })
                .setNegativeButton(JacStrings.get(activity, R.string.jac_devscan_notice_later), (d, which) -> {
                    HumogramConfig.setDeviceScanConsent(app, 0);
                    HumogramConfig.setDeviceScanOnLaunch(app, false);
                })
                .create();
        // Tapping outside or Back is neither answer: nothing is stored, and the
        // notice comes back next launch.
        if (fragment.showDialog(dialog) == null) {
            // A fragment transition swallowed it; let the next resume retry.
            started.set(false);
        }
    }

    // ------------------------------------------------------------------
    // The scan
    // ------------------------------------------------------------------

    private static void startScan(BaseFragment fragment) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        // Asked again here, not only in check(): a session revoked on the
        // server logs the account out a few seconds into a launch, after the
        // notice is already up, and "Turn on" tapped on top of the login
        // screen must not scan. The flag is handed back so the launch after
        // the next sign-in is not skipped.
        if (!UserConfig.getInstance(fragment.getCurrentAccount()).isClientActivated()) {
            started.set(false);
            return;
        }
        final Context app = activity.getApplicationContext();
        try {
            DeviceScanUi.ensureWatching(app);
            final DeviceScanner scanner = DeviceScanner.getInstance(app);
            scanner.start(DeviceScanner.TRIGGER_LAUNCH, new DeviceScanner.Listener() {
                private boolean delivered;

                @Override
                public void onProgress(int done, int total, String currentLabel) {
                }

                @Override
                public void onFinished(DeviceScanReport report) {
                    if (delivered) {
                        return;
                    }
                    delivered = true;
                    DeviceScanUi.remember(report);
                    // Deferred: removing ourselves from inside the scanner's
                    // own dispatch loop is not ours to assume is safe.
                    final DeviceScanner.Listener self = this;
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            scanner.removeListener(self);
                        } catch (Throwable ignored) {
                        }
                    });
                    deliver(fragment, report);
                }
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void deliver(BaseFragment fragment, DeviceScanReport report) {
        if (report == null) {
            return;
        }
        if (!isInFront(fragment) || isLocked() || !showOutcome(fragment, report)) {
            pendingOutcome = report;
        }
    }

    private static void showPendingOutcome(BaseFragment fragment) {
        DeviceScanReport report = pendingOutcome;
        if (report == null) {
            return;
        }
        // The user may have scanned again on the page since (and uninstalled
        // what we were about to warn about): speak from the newest report.
        final DeviceScanReport newest = DeviceScanUi.peekReport();
        if (newest != null && newest.finishedAt > report.finishedAt) {
            report = newest;
        }
        pendingOutcome = null;
        if (!showOutcome(fragment, report)) {
            pendingOutcome = report;
        }
    }

    /**
     * Say the one thing this scan warrants. Returns false only when a dialog
     * could not be shown (a transition was running), so the caller keeps the
     * outcome for the next resume.
     */
    private static boolean showOutcome(BaseFragment fragment, DeviceScanReport report) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return false;
        }
        final Context context = activity;

        // Dangerous or malicious, not trusted: a Bulletin every launch, not a
        // dialog. A dialog over the chat list blocks the app for something the
        // user may already know about, and Telegram itself never greets a
        // launch with one; the Bulletin says it, offers the page, and goes.
        // Apps and files are worded apart: an installer sitting in downloads
        // has not infected anything, and saying "dangerous apps" about it
        // frightens people into thinking it has.
        final ArrayList<String> severeApps = new ArrayList<>();
        final ArrayList<String> severeFiles = new ArrayList<>();
        if (report.apps != null) {
            for (DeviceScanReport.AppResult app : report.apps) {
                if (app != null && app.risk >= DeviceScanUi.RISK_DANGEROUS && !DeviceScanUi.isTrusted(context, app)) {
                    severeApps.add(DeviceScanUi.appLabel(app));
                }
            }
        }
        if (report.apkFiles != null) {
            for (DeviceScanReport.FileResult file : report.apkFiles) {
                if (file != null && file.risk >= DeviceScanUi.RISK_DANGEROUS) {
                    severeFiles.add(DeviceScanUi.fileLabel(file));
                }
            }
        }
        if (!severeApps.isEmpty()) {
            dangerBulletin(fragment, severeApps.size() == 1
                    ? JacStrings.get(context, R.string.jac_devscan_bulletin_danger_app_one, severeApps.get(0))
                    : JacStrings.get(context, R.string.jac_devscan_bulletin_danger_app_many, severeApps.size()),
                    JacStrings.get(context, R.string.jac_devscan_bulletin_danger_app_hint));
            return true;
        }
        if (!severeFiles.isEmpty()) {
            dangerBulletin(fragment, severeFiles.size() == 1
                    ? JacStrings.get(context, R.string.jac_devscan_bulletin_danger_file_one, severeFiles.get(0))
                    : JacStrings.get(context, R.string.jac_devscan_bulletin_danger_file_many, severeFiles.size()),
                    JacStrings.get(context, severeFiles.size() == 1
                            ? R.string.jac_devscan_bulletin_danger_file_hint_one
                            : R.string.jac_devscan_bulletin_danger_file_hint_many));
            return true;
        }

        // Suspicious only: one Bulletin per app version, ever.
        final ArrayList<String> fresh = new ArrayList<>();
        boolean anySuspicious = false;
        if (report.apps != null) {
            for (DeviceScanReport.AppResult app : report.apps) {
                if (app != null && app.risk == DeviceScanUi.RISK_SUSPICIOUS && !DeviceScanUi.isTrusted(context, app)) {
                    anySuspicious = true;
                    if (HumogramConfig.markThreatNotified(context, app.trustKey())) {
                        fresh.add(DeviceScanUi.appLabel(app));
                    }
                }
            }
        }
        if (report.apkFiles != null) {
            for (DeviceScanReport.FileResult file : report.apkFiles) {
                if (file != null && file.risk == DeviceScanUi.RISK_SUSPICIOUS) {
                    anySuspicious = true;
                    if (file.ephemeral) {
                        // A file under the cache root, where secret-chat
                        // documents live: its path may not be written to the
                        // notified set either, so there is nothing to remember
                        // it by and it is named again next launch — until the
                        // user deletes it, which is the point.
                        fresh.add(DeviceScanUi.fileLabel(file));
                    } else if (HumogramConfig.markThreatNotified(context, "file:" + file.path + "|" + file.size)) {
                        fresh.add(DeviceScanUi.fileLabel(file));
                    }
                }
            }
        }
        if (!fresh.isEmpty()) {
            final String text = fresh.size() == 1
                    ? JacStrings.get(context, R.string.jac_devscan_bulletin_suspicious_one, fresh.get(0))
                    : JacStrings.get(context, R.string.jac_devscan_bulletin_suspicious_many, fresh.size());
            bulletin(fragment, R.raw.chats_infotip, text, true);
            return true;
        }
        if (anySuspicious) {
            // Already told about each of them; never follow that with "no
            // threats".
            return true;
        }

        // Nothing found.
        if (report.appsChecked <= 0) {
            return true; // a scan that saw nothing proves nothing — say nothing
        }
        if (!HumogramConfig.isDeviceScanFirstCleanShown(context)) {
            HumogramConfig.setDeviceScanFirstCleanShown(context);
            bulletin(fragment, R.raw.contact_check,
                    JacStrings.get(context, R.string.jac_devscan_bulletin_clean_first, report.appsChecked),
                    false);
        } else if (report.newOrChanged > 0) {
            bulletin(fragment, R.raw.contact_check,
                    JacStrings.get(context, R.string.jac_devscan_bulletin_clean_new, report.newOrChanged),
                    false);
        }
        return true;
    }

    /** Upstream's error animation, the title, one line of what to do, and "Review". */
    private static void dangerBulletin(BaseFragment fragment, String text, String subtext) {
        try {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, text, subtext,
                    JacStrings.get(fragment.getParentActivity(), R.string.jac_devscan_review),
                    () -> fragment.presentFragment(new HumogramSettingsActivity())).show();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void bulletin(BaseFragment fragment, int rawIcon, String text, boolean review) {
        try {
            final BulletinFactory factory = BulletinFactory.of(fragment);
            if (review) {
                factory.createSimpleBulletin(rawIcon, text,
                        JacStrings.get(fragment.getParentActivity(), R.string.jac_devscan_review),
                        () -> fragment.presentFragment(new HumogramSettingsActivity())).show();
            } else {
                factory.createSimpleBulletin(rawIcon, text).show();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
