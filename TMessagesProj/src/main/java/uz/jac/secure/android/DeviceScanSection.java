package uz.jac.secure.android;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import uz.jac.secure.core.model.SignalCodes;

/**
 * The device virus scanner, as rows at the top of {@link HumogramSettingsActivity}:
 * the scan's results, its one action ("Scan now") and its one setting.
 *
 * <h3>A section, not a page</h3>
 *
 * The scanner is the app's main feature, so it is the first thing on the
 * Humogram page rather than a page of its own one tap further in. This class
 * holds everything the scanner part of that page needs — state, rows, clicks,
 * dialogs — so the page itself stays a short list of what else it shows. The
 * host page passes its {@link BaseFragment} (for dialogs, bulletins and the
 * activity) and a way to re-fill its list; it forwards
 * {@link #fillItems}, {@link #onClick}, {@link #onResume} and
 * {@link #onDestroy}.
 *
 * <h3>Built from upstream parts</h3>
 *
 * Every row is a stock cell described as a {@link UItem}: {@code TextCell} for
 * apps, files and buttons, {@code TextCheckCell} for the launch switch,
 * {@code HeaderCell} and {@code TextInfoPrivacyCell} around them. Dialogs are
 * upstream {@link AlertDialog}s, confirmations are Bulletins. The single custom
 * view is {@link DeviceScanSummaryView}, the verdict block at the top, which
 * upstream has no cell for.
 *
 * <h3>App rows with the app's own icon</h3>
 *
 * A threat is recognised by its icon faster than by its name, so app rows use
 * {@code TextCell}'s Drawable-icon form with a second line, the way
 * ThemeActivity builds its two-line rows. Three things have to be undone on
 * every bind because cells are recycled between row kinds: the adapter tints
 * every TextCell icon grey (and re-applies the tint from the image view's tag
 * on attach), and a TextCell keeps whatever subtitle and height the previous
 * row gave it. Hence {@link #bindTwoLine} and {@link #plain} — every TEXT row
 * on this page goes through one of them. Icons themselves are loaded once per
 * report on a background thread by {@link DeviceScanIcons}; a neutral glyph
 * stands in until they arrive.
 *
 * <h3>Sections</h3>
 *
 * <ol>
 *   <li>The verdict block and "Scan now".</li>
 *   <li>Threats: untrusted apps (and APK files) the scan rates suspicious or
 *       worse, with the reason in a few words and the risk in colour.</li>
 *   <li>Worth a look: apps installed outside a store — not threats, listed so
 *       the user can recognise them; the first few, then "Show more".</li>
 *   <li>APK files in Humogram's downloads, deletable from here.</li>
 *   <li>Trusted apps, each one tap from being untrusted again.</li>
 * </ol>
 *
 * The launch switch is not in these rows: the host puts {@link #launchCheck}
 * in its security card next to link protection, so the page has one card of
 * switches rather than two. There is no engine to choose: every check runs on
 * this phone, and nothing is sent anywhere.
 *
 * <h3>Nothing here scans</h3>
 *
 * The section only asks {@link DeviceScanner} to run and listens. A scan the
 * launch hook started before the page was opened simply shows up here as
 * "Checking…" with its progress, and a scan still running when the page is
 * closed carries on and lands in the report the next visit reads.
 */
final class DeviceScanSection {

    /** Re-fills the host's list. */
    interface Host {
        void refresh(boolean animated);
    }

    /**
     * Row ids. Above the host page's own ids (single digits and
     * 100 + ornament), so one onClick can route both.
     */
    private static final int BUTTON_SCAN = 1_001;
    private static final int BUTTON_SHOW_MORE = 1_002;
    private static final int CHECK_ON_LAUNCH = 1_003;

    /** Per-section id bases for list rows; the row's object carries the result. */
    private static final int ROW_THREAT = 10_000;
    private static final int ROW_NOTICE = 20_000;
    private static final int ROW_FILE = 30_000;
    private static final int ROW_TRUSTED = 40_000;

    /** "Worth a look" rows shown before "Show more". */
    private static final int NOTICE_PREVIEW = 3;
    /** App icon size in the rows, per TextCell's two-line layout. */
    private static final int ICON_DP = 30;

    private final BaseFragment fragment;
    private final Host host;

    private DeviceScanner scanner;
    private DeviceScanReport report;
    private boolean reportLoaded;
    private boolean scanning;
    private int progressDone;
    private int progressTotal;
    private String progressLabel;

    private boolean noticeExpanded;
    /** Set when the user left for the system uninstaller: re-check on return. */
    private boolean rescanOnResume;
    private boolean destroyed;

    /** Shortest time the spinning ring stays up for a scan started here. */
    private static final long MIN_SPIN_MS = 1200;
    /** When this page started the running scan; 0 when it did not. */
    private long scanStartedAt;

    /** Threat numbers from the last fill, for progress-only summary rebinds. */
    private int lastThreatCount;
    private int lastWorstRisk;

    private DeviceScanSummaryView summaryView;
    private final HashMap<String, Drawable> icons = new HashMap<>();
    /**
     * One placeholder per package, not one shared: ImageView applies its
     * colour filter to the drawable itself, so a single instance in several
     * cells would carry one cell's tint into the others. Stable per package so
     * a re-fill does not look like a new row to the diff.
     */
    private final HashMap<String, Drawable> placeholders = new HashMap<>();
    private final HashSet<String> iconsRequested = new HashSet<>();
    /** Files deleted from this page, hidden until the next report replaces the list. */
    private final HashSet<String> deletedPaths = new HashSet<>();

    private final DeviceScanner.Listener scanListener = new DeviceScanner.Listener() {
        @Override
        public void onProgress(int done, int total, String currentLabel) {
            if (destroyed) {
                return;
            }
            progressDone = done;
            progressTotal = total;
            progressLabel = currentLabel;
            if (!scanning) {
                // A scan started elsewhere (the launch hook) while we are open.
                scanning = true;
                refresh(true);
            } else {
                bindSummary(fragment.getContext());
            }
        }

        @Override
        public void onFinished(DeviceScanReport finished) {
            if (destroyed) {
                return;
            }
            // An incremental scan can finish in a few hundred milliseconds,
            // before the ring has made one turn: the result would arrive
            // looking like nothing happened. Let the spinner run its course,
            // as the storage screen's count does, then show the verdict.
            final long shown = SystemClock.elapsedRealtime() - scanStartedAt;
            if (scanStartedAt > 0 && shown < MIN_SPIN_MS) {
                AndroidUtilities.runOnUIThread(() -> onFinished(finished), MIN_SPIN_MS - shown);
                scanStartedAt = 0;
                return;
            }
            scanStartedAt = 0;
            scanning = false;
            if (finished != null) {
                report = finished;
                reportLoaded = true;
                deletedPaths.clear();
                DeviceScanUi.remember(finished);
            }
            requestIcons();
            // Not animated: every result row is new, and a diff of a hundred
            // removals and insertions is noise, not information.
            refresh(false);
        }
    };

    /**
     * Call from the host's {@code createView}, before {@code super.createView}
     * fills the list for the first time, then call {@link #start} after it.
     */
    DeviceScanSection(BaseFragment fragment, Context context, Host host) {
        this.fragment = fragment;
        this.host = host;
        summaryView = new DeviceScanSummaryView(context);

        try {
            scanner = DeviceScanner.getInstance(context.getApplicationContext());
            // Listen before asking whether it runs, so a scan that finishes in
            // between still reaches us.
            scanner.addListener(scanListener);
            scanning = scanner.isRunning();
        } catch (Throwable t) {
            FileLog.e(t);
            scanner = null;
        }
        // So the shared report cache follows a scan that finishes after this
        // page is closed — Settings' status row reads it next.
        DeviceScanUi.ensureWatching(context);
        report = DeviceScanUi.peekReport();
        reportLoaded = DeviceScanUi.isReportLoaded();
    }

    /** Loads the last report (once the list exists to show it) and the app icons. */
    void start(Context context) {
        if (reportLoaded) {
            requestIcons();
        } else {
            DeviceScanUi.loadReport(context, () -> {
                if (destroyed) {
                    return;
                }
                if (report == null) {
                    report = DeviceScanUi.peekReport();
                }
                reportLoaded = true;
                requestIcons();
                refresh(false);
            });
        }
    }

    void onResume() {
        if (destroyed) {
            return;
        }
        if (scanning && scanner != null && !scanner.isRunning()) {
            // Finished while we were not looking (the listener is still
            // attached, but a scan can end between a start and its first
            // tick): take whatever report is newest.
            scanning = false;
            final DeviceScanReport newest = DeviceScanUi.peekReport();
            if (newest != null && (report == null || newest.finishedAt > report.finishedAt)) {
                report = newest;
                deletedPaths.clear();
                requestIcons();
            }
        }
        if (rescanOnResume) {
            // Back from the system uninstaller: whatever the user chose there,
            // the list should now say so. Incremental, so this is quick.
            rescanOnResume = false;
            startScan();
            return;
        }
        // Trust and the launch switch can both have changed on another path;
        // re-fill is cheap.
        refresh(true);
    }

    void onDestroy() {
        destroyed = true;
        if (scanner != null) {
            try {
                scanner.removeListener(scanListener);
            } catch (Throwable ignored) {
            }
        }
    }

    private void refresh(boolean animated) {
        if (destroyed || fragment.getContext() == null) {
            return;
        }
        host.refresh(animated);
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /**
     * The scanner's rows, from the verdict block down to the trusted apps.
     * {@code afterThreats} adds the host's rows that belong between what needs
     * acting on now and the longer lists below (the device check); may be null.
     */
    void fillItems(ArrayList<UItem> items, Runnable afterThreats) {
        final Context context = fragment.getContext();
        if (context == null) {
            return;
        }

        final ArrayList<DeviceScanReport.AppResult> threatApps = new ArrayList<>();
        final ArrayList<DeviceScanReport.AppResult> noticeApps = new ArrayList<>();
        final ArrayList<DeviceScanReport.AppResult> trustedApps = new ArrayList<>();
        final ArrayList<DeviceScanReport.FileResult> threatFiles = new ArrayList<>();
        final ArrayList<DeviceScanReport.FileResult> otherFiles = new ArrayList<>();
        int worst = DeviceScanUi.RISK_NONE;
        if (report != null) {
            if (report.apps != null) {
                for (DeviceScanReport.AppResult app : report.apps) {
                    if (app == null || app.risk <= DeviceScanUi.RISK_NONE) {
                        continue;
                    }
                    if (DeviceScanUi.isTrusted(context, app)) {
                        trustedApps.add(app);
                    } else if (app.risk >= DeviceScanUi.RISK_SUSPICIOUS) {
                        threatApps.add(app);
                        worst = Math.max(worst, app.risk);
                    } else {
                        noticeApps.add(app);
                    }
                }
            }
            if (report.apkFiles != null) {
                for (DeviceScanReport.FileResult file : report.apkFiles) {
                    if (file == null || deletedPaths.contains(file.path)) {
                        continue;
                    }
                    if (file.risk >= DeviceScanUi.RISK_SUSPICIOUS) {
                        threatFiles.add(file);
                        worst = Math.max(worst, file.risk);
                    } else {
                        otherFiles.add(file);
                    }
                }
            }
        }
        lastThreatCount = threatApps.size() + threatFiles.size();
        lastWorstRisk = worst;

        // 1. Verdict and the one action.
        bindSummary(context);
        // On the page background, not in a card — as the storage screen's ring.
        items.add(UItem.asCustomShadow(summaryView));
        final boolean canScan = ScannerBootstrap.isInstalled() && scanner != null;
        items.add(plain(UItem.asButton(BUTTON_SCAN, JacStrings.get(context, scanning
                ? R.string.jac_devscan_status_scanning : R.string.jac_devscan_scan_now))
                .accent()
                .setEnabled(canScan && !scanning)));
        items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_devscan_intro)));

        // 2. Threats.
        if (!threatApps.isEmpty() || !threatFiles.isEmpty()) {
            items.add(UItem.asHeader(JacStrings.get(context, R.string.jac_devscan_threats_header)));
            int i = 0;
            for (DeviceScanReport.AppResult app : threatApps) {
                items.add(appRow(context, ROW_THREAT + i++, app, null));
            }
            for (DeviceScanReport.FileResult file : threatFiles) {
                items.add(fileRow(context, ROW_THREAT + i++, file));
            }
            items.add(UItem.asShadow(null));
        }

        if (afterThreats != null) {
            afterThreats.run();
        }

        // 3. Worth a look — collapsed after a few.
        if (!noticeApps.isEmpty()) {
            items.add(UItem.asHeader(JacStrings.get(context, R.string.jac_devscan_attention_header)));
            final boolean showAll = noticeExpanded || noticeApps.size() <= NOTICE_PREVIEW + 1;
            final int shown = showAll ? noticeApps.size() : NOTICE_PREVIEW;
            for (int i = 0; i < shown; i++) {
                final DeviceScanReport.AppResult app = noticeApps.get(i);
                items.add(appRow(context, ROW_NOTICE + i, app, null));
            }
            if (!showAll) {
                items.add(plain(UItem.asButton(BUTTON_SHOW_MORE, JacStrings.get(context,
                        R.string.jac_devscan_show_more, noticeApps.size() - shown)).accent()));
            }
            items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_devscan_attention_info)));
        }

        // 4. Loose installers in our own download folders.
        if (!otherFiles.isEmpty()) {
            items.add(UItem.asHeader(JacStrings.get(context, R.string.jac_devscan_files_header)));
            for (int i = 0; i < otherFiles.size(); i++) {
                items.add(fileRow(context, ROW_FILE + i, otherFiles.get(i)));
            }
            items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_devscan_files_info)));
        }

        // 5. Trusted apps.
        if (!trustedApps.isEmpty()) {
            items.add(UItem.asHeader(JacStrings.get(context, R.string.jac_devscan_trusted_header)));
            for (int i = 0; i < trustedApps.size(); i++) {
                items.add(appRow(context, ROW_TRUSTED + i, trustedApps.get(i),
                        JacStrings.get(context, R.string.jac_devscan_risk_trusted)));
            }
            items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_devscan_trusted_info)));
        }
    }

    /** "Check at every launch", for the host's security card. */
    UItem launchCheck(Context context) {
        return UItem.asCheck(CHECK_ON_LAUNCH, JacStrings.get(context, R.string.jac_devscan_on_launch))
                .setChecked(DeviceScanUi.isOnLaunchEnabled(context));
    }

    /**
     * A two-line app row: real icon, label, a few words of reason, the risk
     * word in colour. The result rides on {@code object2} so a tap needs no
     * id bookkeeping.
     *
     * @param valueOverride the value-column text, or null for the risk word —
     *                      which is chosen from the lead finding, not from the
     *                      level alone (see {@link DeviceScanUi#riskWord})
     */
    private UItem appRow(Context context, int id, DeviceScanReport.AppResult app, CharSequence valueOverride) {
        Drawable icon = icons.get(app.packageName);
        if (icon == null) {
            icon = placeholders.get(app.packageName);
            if (icon == null) {
                icon = new DeviceScanGlyphDrawable(JacIcons.Glyph.DEVICE, ICON_DP,
                        Theme.key_windowBackgroundWhiteGrayIcon);
                placeholders.put(app.packageName, icon);
            }
        }
        final ScanFinding lead = DeviceScanUi.leadFinding(app.findings);
        final String subtitle;
        if (lead == null || SignalCodes.DEV_SIDELOADED.equals(lead.code)) {
            // For an app whose only note is "not from a store", where it did
            // come from is the useful thing to say.
            subtitle = DeviceScanUi.sourceText(context, app);
        } else {
            subtitle = DeviceScanUi.shortReason(context, lead);
        }
        final UItem item = UItem.asButton(id, icon, DeviceScanUi.appLabel(app));
        item.textValue = valueOverride != null
                ? valueOverride : DeviceScanUi.riskValue(context, app.risk, lead);
        item.object2 = app;
        return item.onBind(view -> bindTwoLine(view, subtitle, true));
    }

    /** A two-line APK file row, with upstream's download glyph as its icon. */
    private UItem fileRow(Context context, int id, DeviceScanReport.FileResult file) {
        final ScanFinding lead = DeviceScanUi.leadFinding(file.findings);
        final String subtitle;
        if (file.risk >= DeviceScanUi.RISK_SUSPICIOUS) {
            subtitle = DeviceScanUi.shortReason(context, lead);
        } else {
            subtitle = fileDetailsLine(context, file);
        }
        final UItem item = UItem.asButton(id, R.drawable.msg_download, DeviceScanUi.fileLabel(file),
                DeviceScanUi.riskValue(context, file.risk, lead));
        item.object2 = file;
        return item.onBind(view -> bindTwoLine(view, subtitle, false));
    }

    /**
     * The per-bind half of a two-line row. See the class comment for why each
     * line exists: the cell may have been a one-line button a moment ago.
     */
    private static void bindTwoLine(View view, CharSequence subtitle, boolean ownIcon) {
        if (!(view instanceof TextCell)) {
            return;
        }
        final TextCell cell = (TextCell) view;
        cell.setSubtitle(subtitle);
        cell.heightDp = 60;
        if (ownIcon) {
            // ThemeActivity's two-line geometry, sized for a 30 dp icon.
            cell.offsetFromImage = 64;
            cell.imageLeft = 20;
            // The adapter tinted the icon grey and remembered that in the
            // image view's tag; a colour app icon must be neither.
            cell.imageView.setTag(null);
            cell.imageView.setColorFilter(null);
            cell.imageView.setPadding(0, 0, 0, 0);
        }
    }

    /** One-line rows: clear what a recycled two-line row left behind. */
    private static UItem plain(UItem item) {
        return item.onBind(view -> {
            if (view instanceof TextCell) {
                final TextCell cell = (TextCell) view;
                cell.setSubtitle(null);
                cell.heightDp = 50;
            }
        });
    }

    // ------------------------------------------------------------------
    // Summary
    // ------------------------------------------------------------------

    private void bindSummary(Context context) {
        if (summaryView == null || context == null) {
            return;
        }
        if (!ScannerBootstrap.isInstalled() || scanner == null) {
            summaryView.bind(DeviceScanSummaryView.STATE_IDLE,
                    JacStrings.get(context, R.string.jac_devscan_summary_unavailable),
                    JacStrings.get(context, R.string.jac_devscan_summary_unavailable_hint));
            return;
        }
        if (scanning || !reportLoaded) {
            // The ring spins for the scan and for the few milliseconds the last
            // report takes to come off disk alike; only the words differ.
            final boolean counted = scanning && progressTotal > 0;
            final String title = counted
                    ? JacStrings.get(context, R.string.jac_devscan_summary_scanning,
                            Math.min(progressDone, progressTotal), progressTotal)
                    : JacStrings.get(context, scanning
                            ? R.string.jac_devscan_summary_preparing : R.string.jac_devscan_title);
            summaryView.bind(DeviceScanSummaryView.STATE_LOADING, title, scanning ? progressLabel : null);
            return;
        }
        if (report == null) {
            summaryView.bind(DeviceScanSummaryView.STATE_IDLE,
                    JacStrings.get(context, R.string.jac_devscan_summary_never),
                    JacStrings.get(context, R.string.jac_devscan_summary_never_hint));
            return;
        }
        if (DeviceScanUi.isEmptyFailure(report)) {
            // Checked nothing, so it may not say "no threats".
            summaryView.bind(DeviceScanSummaryView.STATE_WARNING,
                    JacStrings.get(context, R.string.jac_devscan_summary_failed),
                    JacStrings.get(context, R.string.jac_devscan_summary_failed_hint));
            return;
        }
        final CharSequence details = withProtection(context, reportDetails(context, report));
        if (lastThreatCount > 0) {
            // Red for anything worth acting on: the rows below say how bad
            // each one is; the ring only has to say "not fine".
            summaryView.bind(DeviceScanSummaryView.STATE_ALERT,
                    JacStrings.get(context, R.string.jac_devscan_summary_threats, lastThreatCount),
                    details);
        } else {
            // "No threats found" — a statement about what the checks saw, not
            // a CLEAN verdict on any one app; the wording keeps that line.
            summaryView.bind(DeviceScanSummaryView.STATE_CLEAN,
                    JacStrings.get(context, R.string.jac_devscan_summary_clean), details);
        }
    }

    /**
     * "Protection level: 80%" in its level's colour above the report details —
     * the number the Settings card shows, from the same
     * {@link SecurityCheckup#score} call.
     */
    private CharSequence withProtection(Context context, CharSequence details) {
        final int score = SecurityCheckup.score(context, report, fragment.getCurrentAccount());
        if (score < 0) {
            return details;
        }
        final String line = JacStrings.get(context, R.string.jac_protection_level, score);
        final SpannableStringBuilder sb = new SpannableStringBuilder(line);
        sb.setSpan(new ForegroundColorSpan(SecurityCheckup.levelColor(score, fragment.getResourceProvider())),
                0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb.append('\n').append(details);
    }

    /** When, how much, and where. */
    private static CharSequence reportDetails(Context context, DeviceScanReport report) {
        final StringBuilder sb = new StringBuilder();
        if (report.finishedAt > 0) {
            sb.append(JacStrings.get(context, R.string.jac_devscan_summary_checked_at,
                    LocaleController.formatDateTime(report.finishedAt / 1000, true)));
            sb.append('\n');
        }
        sb.append(JacStrings.get(context, R.string.jac_devscan_summary_apps, report.appsChecked));
        final int files = report.apkFiles != null ? report.apkFiles.size() : 0;
        if (files > 0) {
            sb.append(" · ").append(JacStrings.get(context, R.string.jac_devscan_summary_files, files));
        }
        sb.append('\n').append(JacStrings.get(context, R.string.jac_devscan_engine_local));
        return sb;
    }

    // ------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------

    /** True when the row was one of the scanner's and has been handled. */
    boolean onClick(UItem item, View view) {
        if (item.object2 instanceof DeviceScanReport.AppResult) {
            showAppDetails((DeviceScanReport.AppResult) item.object2);
            return true;
        }
        if (item.object2 instanceof DeviceScanReport.FileResult) {
            showFileDetails((DeviceScanReport.FileResult) item.object2);
            return true;
        }
        final Context context = fragment.getContext();
        if (context == null) {
            return false;
        }
        switch (item.id) {
            case BUTTON_SCAN:
                startScan();
                return true;
            case BUTTON_SHOW_MORE:
                noticeExpanded = true;
                refresh(true);
                return true;
            case CHECK_ON_LAUNCH: {
                final boolean on = !DeviceScanUi.isOnLaunchEnabled(context);
                HumogramConfig.setDeviceScanOnLaunch(context, on);
                if (on) {
                    // Turning the switch on here is the same affirmative
                    // choice as "Turn on" in the launch notice, which is
                    // therefore not asked again.
                    HumogramConfig.setDeviceScanConsent(context, 1);
                }
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(on);
                }
                // The cached item must agree with the cell, or a
                // recycled rebind draws the old state.
                item.checked = on;
                return true;
            }
            default:
                return false;
        }
    }

    private void startScan() {
        if (scanner == null || !ScannerBootstrap.isInstalled() || fragment.getContext() == null) {
            return;
        }
        if (!scanner.isRunning()) {
            try {
                // null: this section is already a registered listener.
                scanner.start(DeviceScanner.TRIGGER_MANUAL, null);
            } catch (Throwable t) {
                FileLog.e(t);
                return;
            }
        }
        scanning = true;
        scanStartedAt = SystemClock.elapsedRealtime();
        progressDone = 0;
        progressTotal = 0;
        progressLabel = null;
        refresh(true);
    }

    // ------------------------------------------------------------------
    // App details
    // ------------------------------------------------------------------

    private void showAppDetails(DeviceScanReport.AppResult app) {
        final Activity activity = fragment.getParentActivity();
        final Context context = fragment.getContext();
        if (activity == null || context == null) {
            return;
        }
        final boolean trusted = DeviceScanUi.isTrusted(context, app);
        final boolean accessibility = DeviceScanUi.has(app.findings, SignalCodes.DEV_ACCESSIBILITY_ACTIVE)
                || DeviceScanUi.has(app.findings, SignalCodes.DEV_OVERLAY_ACCESSIBILITY);
        final boolean admin = DeviceScanUi.has(app.findings, SignalCodes.DEV_DEVICE_ADMIN_ACTIVE);
        final boolean smsRole = DeviceScanUi.has(app.findings, SignalCodes.DEV_SMS_ROLE);

        final StringBuilder message = new StringBuilder(appVerdict(context, app));
        appendReasons(context, message, app.findings);
        // The order a victim has to act in: an active accessibility service
        // can tap "Cancel" on the uninstall prompt, and an active device admin
        // cannot be uninstalled at all, so those come off first.
        if (accessibility) {
            message.append("\n\n").append(JacStrings.get(context, R.string.jac_devscan_a11y_advice));
        }
        if (admin) {
            message.append("\n\n").append(JacStrings.get(context, R.string.jac_devscan_admin_advice));
        }
        if (smsRole) {
            message.append("\n\n").append(JacStrings.get(context, R.string.jac_devscan_sms_advice));
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(DeviceScanUi.appLabel(app))
                .setSubtitle(appSubtitle(context, app))
                .setMessage(message.toString());
        if (!app.system) {
            builder.setPositiveButton(JacStrings.get(context, R.string.jac_devscan_uninstall),
                    (d, which) -> uninstall(app));
        }
        builder.setNegativeButton(JacStrings.get(context, R.string.jac_devscan_app_info),
                (d, which) -> openAppInfo(app.packageName));
        builder.setNeutralButton(JacStrings.get(context, trusted
                        ? R.string.jac_devscan_untrust : R.string.jac_devscan_trust),
                (d, which) -> setTrusted(app, !trusted));
        if (accessibility) {
            builder.setButton(AlertDialog.BUTTON_NEGATIVE_2,
                    JacStrings.get(context, R.string.jac_devscan_open_a11y),
                    (d, which) -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (admin) {
            builder.setButton(AlertDialog.BUTTON_NEGATIVE_2,
                    JacStrings.get(context, R.string.jac_devscan_open_security),
                    (d, which) -> openSettings(Settings.ACTION_SECURITY_SETTINGS));
        } else if (smsRole) {
            // The advice says to hand the SMS role back to the real Messages
            // app; this is the screen that does it (API 24+, above minSdk).
            builder.setButton(AlertDialog.BUTTON_NEGATIVE_2,
                    JacStrings.get(context, R.string.jac_devscan_open_default_apps),
                    (d, which) -> openSettings(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        }
        final AlertDialog dialog = builder.create();
        if (fragment.showDialog(dialog) != null && !app.system) {
            tint(dialog, DialogInterface.BUTTON_POSITIVE, Theme.key_text_RedBold);
        }
    }

    /**
     * The opening sentence of the details dialog.
     *
     * <p>Chosen from the lead finding as well as the level, for the reason
     * {@link DeviceScanUi#riskWord} gives: "This app is malware" is a detection
     * claim, and an app scored up to MALICIOUS because its package id resembles
     * a bank's has not been detected as anything. It gets the counterfeit
     * sentence, which says what was found and what to do about it without
     * accusing a possibly legitimate publisher of shipping malware.
     */
    private static String appVerdict(Context context, DeviceScanReport.AppResult app) {
        final ScanFinding lead = DeviceScanUi.leadFinding(app.findings);
        if (DeviceScanUi.isImpersonation(lead) && app.risk >= DeviceScanUi.RISK_SUSPICIOUS) {
            final String brand = lead.arg(0);
            if (brand != null && !brand.isEmpty()) {
                return JacStrings.get(context, R.string.jac_devscan_verdict_counterfeit, brand);
            }
        }
        switch (app.risk) {
            case DeviceScanUi.RISK_MALICIOUS:
                return JacStrings.get(context, R.string.jac_devscan_verdict_malicious);
            case DeviceScanUi.RISK_DANGEROUS:
                return JacStrings.get(context, R.string.jac_devscan_verdict_dangerous);
            case DeviceScanUi.RISK_SUSPICIOUS:
                return JacStrings.get(context, R.string.jac_devscan_verdict_suspicious);
            default:
                return JacStrings.get(context, R.string.jac_devscan_verdict_notice);
        }
    }

    /** "com.example.app · Version 1.2 · Installed from a file". */
    private static String appSubtitle(Context context, DeviceScanReport.AppResult app) {
        final StringBuilder sb = new StringBuilder(app.packageName);
        if (!TextUtils.isEmpty(app.versionName)) {
            sb.append(" · ").append(JacStrings.get(context, R.string.jac_devscan_version, app.versionName));
        }
        sb.append(" · ").append(DeviceScanUi.sourceText(context, app));
        return sb.toString();
    }

    private static void appendReasons(Context context, StringBuilder message, List<ScanFinding> findings) {
        final List<String> reasons = DeviceScanUi.longReasons(context, findings);
        if (reasons.isEmpty()) {
            return;
        }
        message.append('\n');
        for (String reason : reasons) {
            message.append("\n• ").append(reason);
        }
    }

    private void uninstall(DeviceScanReport.AppResult app) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        rescanOnResume = true;
        try {
            // ACTION_DELETE needs REQUEST_DELETE_PACKAGES from target 28 on;
            // without it the system uninstaller quietly finishes. App info is
            // the fallback that always works.
            activity.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName)));
        } catch (Throwable t) {
            openAppInfo(app.packageName);
        }
    }

    private void openAppInfo(String packageName) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        rescanOnResume = true;
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName)));
        } catch (Throwable t) {
            rescanOnResume = false;
            FileLog.e(t);
        }
    }

    private void openSettings(String action) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        rescanOnResume = true;
        try {
            activity.startActivity(new Intent(action));
        } catch (Throwable t) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignored) {
                rescanOnResume = false;
            }
        }
    }

    /**
     * Trust is per version (the trust key includes the version code), so an
     * update puts the app back under watch. Trusting something the scanner is
     * sure about, or that has the banking-trojan capabilities switched on,
     * takes one extra confirmation — it is the one choice on this page that
     * turns a warning off.
     */
    private void setTrusted(DeviceScanReport.AppResult app, boolean trust) {
        final Activity activity = fragment.getParentActivity();
        final Context context = fragment.getContext();
        if (activity == null || context == null) {
            return;
        }
        if (!trust || app.risk < DeviceScanUi.RISK_DANGEROUS) {
            applyTrust(app, trust);
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(JacStrings.get(context, R.string.jac_devscan_trust_confirm_title))
                .setMessage(JacStrings.get(context, R.string.jac_devscan_trust_confirm, DeviceScanUi.appLabel(app)))
                .setPositiveButton(JacStrings.get(context, R.string.jac_devscan_trust), (d, which) -> applyTrust(app, true))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        if (fragment.showDialog(dialog) != null) {
            tint(dialog, DialogInterface.BUTTON_POSITIVE, Theme.key_text_RedBold);
        }
    }

    private void applyTrust(DeviceScanReport.AppResult app, boolean trust) {
        final Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        HumogramConfig.setAppTrusted(context, app.trustKey(), trust);
        refresh(true);
        try {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, JacStrings.get(context,
                    trust ? R.string.jac_devscan_trusted_done : R.string.jac_devscan_untrusted_done,
                    DeviceScanUi.appLabel(app))).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // APK files
    // ------------------------------------------------------------------

    private void showFileDetails(DeviceScanReport.FileResult file) {
        final Activity activity = fragment.getParentActivity();
        final Context context = fragment.getContext();
        if (activity == null || context == null) {
            return;
        }
        final StringBuilder message = new StringBuilder(JacStrings.get(context,
                file.risk >= DeviceScanUi.RISK_MALICIOUS
                        ? R.string.jac_devscan_file_verdict_malicious
                        : R.string.jac_devscan_file_verdict_other));
        appendReasons(context, message, file.findings);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(DeviceScanUi.fileLabel(file))
                .setSubtitle(fileDetailsLine(context, file))
                .setMessage(message.toString())
                .setPositiveButton(JacStrings.get(context, R.string.jac_devscan_delete_file), (d, which) -> deleteFile(file))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        if (fragment.showDialog(dialog) != null) {
            tint(dialog, DialogInterface.BUTTON_POSITIVE, Theme.key_text_RedBold);
        }
    }

    /** "12.4 MB · Installs Payme" — size, and the app inside when known. */
    private static String fileDetailsLine(Context context, DeviceScanReport.FileResult file) {
        final StringBuilder sb = new StringBuilder(AndroidUtilities.formatFileSize(file.size));
        final String inside = !TextUtils.isEmpty(file.label) ? file.label : file.packageName;
        if (!TextUtils.isEmpty(inside)) {
            sb.append(" · ").append(JacStrings.get(context, R.string.jac_devscan_file_installs, inside));
        }
        return sb.toString();
    }

    /**
     * Delete on a background thread (it is disk I/O), then hide the row and
     * re-check so the stored report — and the status in Settings — stop
     * counting it.
     *
     * <p>The row may come from a report written days ago, so the file on disk is
     * re-checked before anything is removed: it must still be inside one of
     * Telegram's own media folders, and still have the size and modification
     * time the scan recorded. Without that, the user could delete "app.apk"
     * through Telegram's own UI, download a different document that takes the
     * same name in Telegram Files, and then delete <em>that</em> from a stale
     * row. It also bounds the damage from a report file that was tampered with.
     *
     * <p>No permission or system prompt is involved, but note these folders are
     * only Humogram's own from API 30: below it {@code ImageLoader} puts
     * {@code Telegram/} on shared external storage, where the official Telegram
     * app writes too.
     */
    private void deleteFile(DeviceScanReport.FileResult file) {
        final String path = file.path;
        if (path == null) {
            return;
        }
        final long size = file.size;
        final long mtime = file.mtime;
        Utilities.globalQueue.postRunnable(() -> {
            int result;
            try {
                final File target = new File(path);
                if (!target.exists()) {
                    result = DELETE_OK;
                } else if (!isInMediaFolder(target) || target.length() != size
                        || (mtime > 0 && target.lastModified() != mtime)) {
                    result = DELETE_CHANGED;
                } else {
                    result = target.delete() ? DELETE_OK : DELETE_FAILED;
                }
            } catch (Throwable t) {
                result = DELETE_FAILED;
            }
            final int outcome = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || fragment.getContext() == null) {
                    return;
                }
                try {
                    if (outcome == DELETE_OK) {
                        deletedPaths.add(path);
                        refresh(true);
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_delete,
                                JacStrings.get(fragment.getContext(), R.string.jac_devscan_file_deleted)).show();
                        startScan();
                    } else if (outcome == DELETE_CHANGED) {
                        BulletinFactory.of(fragment).createErrorBulletin(
                                JacStrings.get(fragment.getContext(), R.string.jac_devscan_file_changed)).show();
                        // The row is stale; a re-check makes it go away.
                        startScan();
                    } else {
                        BulletinFactory.of(fragment).createErrorBulletin(
                                JacStrings.get(fragment.getContext(), R.string.jac_devscan_file_delete_failed)).show();
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        });
    }

    private static final int DELETE_OK = 0;
    private static final int DELETE_FAILED = 1;
    /** The file is not the one that was scanned, or is not somewhere we may delete from. */
    private static final int DELETE_CHANGED = 2;

    /**
     * True when {@code target} really is inside one of the folders the scanner
     * walks. {@code checkDirectory}, not {@code getDirectory}: the latter falls
     * back to the cache folder for a type it has none for, which would widen the
     * test rather than narrow it.
     */
    private static boolean isInMediaFolder(File target) {
        final int[] types = {FileLoader.MEDIA_DIR_FILES, FileLoader.MEDIA_DIR_DOCUMENT,
                FileLoader.MEDIA_DIR_CACHE};
        final String canonical;
        try {
            canonical = target.getCanonicalPath();
        } catch (Throwable t) {
            return false;
        }
        for (int type : types) {
            try {
                final File root = FileLoader.checkDirectory(type);
                if (root != null && canonical.startsWith(root.getCanonicalPath() + File.separator)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Ask for the icons of every listed app once per report. Keyed by
     * package; the page holds them for its lifetime only.
     */
    private void requestIcons() {
        final Context context = fragment.getContext();
        if (context == null || report == null || report.apps == null) {
            return;
        }
        final ArrayList<String> wanted = new ArrayList<>();
        for (DeviceScanReport.AppResult app : report.apps) {
            if (app != null && app.risk > DeviceScanUi.RISK_NONE && app.packageName != null
                    && iconsRequested.add(app.packageName)) {
                wanted.add(app.packageName);
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        DeviceScanIcons.load(context, wanted, AndroidUtilities.dp(ICON_DP), loaded -> {
            if (destroyed || loaded.isEmpty()) {
                return;
            }
            icons.putAll(loaded);
            // Plain rebind: only the drawables changed, and an animated diff
            // would treat every row with a new icon as a new row.
            refresh(false);
        });
    }

    private static void tint(AlertDialog dialog, int button, int colorKey) {
        final View view = dialog.getButton(button);
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(Theme.getColor(colorKey));
        }
    }
}
