package uz.jac.secure.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import uz.jac.secure.core.device.AppAssessment;
import uz.jac.secure.core.device.AppRisk;
import uz.jac.secure.core.device.InstalledAppFacts;
import uz.jac.secure.core.device.InstalledAppPolicy;
import uz.jac.secure.core.device.ThreatIntel;
import uz.jac.secure.core.model.Signal;

/**
 * The device virus scanner: checks the apps installed on this phone, and the
 * APK files in Humogram's own folders, for malware.
 *
 * <h3>Where it runs, and where it deliberately does not</h3>
 *
 * In Humogram's own process, on one background thread ({@code jac-devscan},
 * background priority), while the app is in use — started by
 * {@code DeviceScanLaunch} once the chat list is up and unlocked, or by
 * "Scan now" on the settings page. Never as a background service, a
 * WorkManager job or an alarm: a messenger that wakes itself up to inventory
 * the phone is doing something the user did not ask for, and Play would
 * rightly ask why. The thread is created on demand and lets itself die after
 * half a minute idle, so a process that never scans keeps no extra thread.
 *
 * <p>Nothing it reads leaves the phone: there is no network step anywhere in
 * a run. The verdicts come from the core's heuristics and the threat intel
 * bundled in our own APK.
 *
 * <h3>Single flight</h3>
 *
 * One scan at a time. {@link #start} while a scan is running starts nothing;
 * it attaches the caller to the scan already under way, so the launch hook
 * and a user tapping "Scan now" at the same moment share one run and both see
 * its result.
 *
 * <h3>What one run does</h3>
 *
 * <ol>
 *   <li>Reads phone-wide runtime state in bulk (enabled accessibility
 *       services, notification listeners, device admins, the SMS app,
 *       launcher icons) — {@link DeviceAppCollector.RuntimeState}.</li>
 *   <li>Lists every visible package; reuses cached static facts for the
 *       unchanged ones and deep-reads only the new or updated
 *       ({@link DeviceScanCache}). This is what keeps a repeat launch to a
 *       few hundred milliseconds of metadata work.</li>
 *   <li>Fingerprints base.apk (SHA-256, SHA-1, MD5 in one read) of apps
 *       installed outside an app store whose cache key changed, and of any
 *       app that already looks suspicious. Store and system installs are not
 *       hashed: Play-generated splits never match a published sample, and
 *       hashing a phone's worth of apps at every launch would cost seconds of
 *       disk reads on a budget phone.</li>
 *   <li>Judges each app with the core {@link InstalledAppPolicy} — bundled
 *       threat intel plus runtime heuristics — and each APK file with
 *       {@link InstalledAppPolicy#evaluateApkFile}.</li>
 *   <li>Writes the {@link DeviceScanReport} to
 *       {@code getNoBackupFilesDir()/jac-devscan/report.json} and hands it to
 *       the listeners on the UI thread.</li>
 * </ol>
 *
 * <h3>Failure</h3>
 *
 * Nothing thrown here reaches the UI. A package that misbehaves is skipped
 * (see {@link DeviceAppCollector}); a scan that fails or is cancelled as a
 * whole delivers the last good report if there is one — the truthful
 * "last checked at …" — and otherwise an empty report whose
 * {@link DeviceScanReport#error} says why. A failed or cancelled run is never
 * written to disk and never replaces the last good report, so it cannot make
 * the next scan think every app is new.
 */
public final class DeviceScanner {

    public static final int TRIGGER_LAUNCH = 0;
    public static final int TRIGGER_MANUAL = 1;

    public interface Listener {
        /** UI thread. {@code currentLabel} is the app or file being checked; may be null. */
        void onProgress(int done, int total, String currentLabel);

        /** UI thread, never null. */
        void onFinished(DeviceScanReport report);
    }

    private static final String TAG = "jac";
    private static final String REPORT_FILE = "report.json";
    /** Progress is coalesced to at most one UI post per this many ms. */
    private static final long PROGRESS_INTERVAL_MS = 100;

    private static volatile DeviceScanner instance;

    public static DeviceScanner getInstance(Context context) {
        DeviceScanner local = instance;
        if (local == null) {
            synchronized (DeviceScanner.class) {
                local = instance;
                if (local == null) {
                    Context app = context != null ? context.getApplicationContext() : null;
                    if (app == null) {
                        app = context != null ? context : ApplicationLoader.applicationContext;
                    }
                    local = new DeviceScanner(app);
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Context context;
    private final ThreadPoolExecutor executor;
    private final Object lock = new Object();

    /** addListener/removeListener: for as long as a screen is showing. */
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Passed to {@link #start}: told about this one run, then dropped. Guarded by {@link #lock}. */
    private final List<Listener> runListeners = new ArrayList<>();

    private volatile boolean running;
    private volatile boolean cancelled;

    private final Object reportLock = new Object();
    private volatile DeviceScanReport lastReport;
    private volatile boolean lastReportLoaded;

    private volatile int progressDone;
    private volatile int progressTotal;
    private volatile String progressLabel;
    private final AtomicBoolean progressPosted = new AtomicBoolean();
    /** Worker thread only. */
    private long lastProgressAt;

    private DeviceScanner(Context context) {
        this.context = context;
        this.executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable r) {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // Below the UI and below Telegram's own loaders: a
                        // scan must never be the reason a chat stutters.
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        r.run();
                    }
                }, "jac-devscan");
                t.setDaemon(true);
                return t;
            }
        });
        this.executor.allowCoreThreadTimeOut(true);
        try {
            // First job on the thread, so any scan queues behind it: parse the
            // threat intel and load the last report, off the main thread.
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    warmUp();
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // public API
    // ------------------------------------------------------------------

    public boolean isRunning() {
        return running;
    }

    /**
     * Start a scan, or join the one already running.
     *
     * @param trigger          {@link #TRIGGER_LAUNCH} or {@link #TRIGGER_MANUAL}; recorded in the report
     * @param listenerOrNull   told about this run's progress and result, then
     *                         forgotten — unlike {@link #addListener}, it is
     *                         not kept for later scans
     */
    public void start(int trigger, Listener listenerOrNull) {
        final int runTrigger = trigger == TRIGGER_MANUAL ? TRIGGER_MANUAL : TRIGGER_LAUNCH;
        synchronized (lock) {
            if (listenerOrNull != null && !runListeners.contains(listenerOrNull)) {
                runListeners.add(listenerOrNull);
            }
            if (running) {
                if (listenerOrNull != null) {
                    postCurrentProgress(listenerOrNull);
                }
                return;
            }
            running = true;
            cancelled = false;
            progressDone = 0;
            progressTotal = 0;
            progressLabel = null;
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runScan(runTrigger);
                }
            });
        } catch (RejectedExecutionException e) {
            long now = System.currentTimeMillis();
            finish(fallbackReport(DeviceScanReport.failed(runTrigger, now, "rejected")));
        }
    }

    /** Keep telling {@code l} about every scan until {@link #removeListener}. */
    public void addListener(Listener l) {
        if (l == null) {
            return;
        }
        listeners.addIfAbsent(l);
        if (running) {
            postCurrentProgress(l);
        }
    }

    /** Stop telling {@code l} anything — including about a run it joined through {@link #start}. */
    public void removeListener(Listener l) {
        if (l == null) {
            return;
        }
        listeners.remove(l);
        synchronized (lock) {
            runListeners.remove(l);
        }
    }

    /**
     * The last completed report, or null when there has never been one.
     *
     * <p>Kept in memory; loaded from disk by the scanner thread at startup
     * (see {@link ScannerBootstrap}). Called on the main thread before that
     * load has finished it returns null rather than read the file there — a
     * window of milliseconds after process start, long before anyone can
     * reach the page that asks.
     */
    public DeviceScanReport getLastReport() {
        DeviceScanReport report = lastReport;
        if (report != null || lastReportLoaded) {
            return report;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return null;
        }
        return loadLastReport();
    }

    /** Stop the running scan at the next package or file chunk. Its listeners get the last good report. */
    public void cancel() {
        if (running) {
            cancelled = true;
        }
    }

    // ------------------------------------------------------------------
    // the run
    // ------------------------------------------------------------------

    private void warmUp() {
        try {
            ScannerBootstrap.getInstalledAppPolicy();
        } catch (Throwable t) {
            android.util.Log.e(TAG, "device scanner warm-up failed", t);
        }
        loadLastReport();
    }

    private void runScan(int trigger) {
        long startedAt = System.currentTimeMillis();
        DeviceScanReport report = null;
        String error = null;
        try {
            report = scan(trigger, startedAt);
        } catch (DeviceAppCollector.Cancelled c) {
            error = DeviceScanReport.ERROR_CANCELLED;
        } catch (Throwable t) {
            // The class only, here and in the log: a NameNotFoundException's
            // message is a package name and an IOException's is a path, and the
            // report's error field is shown on screen. FileLog, not
            // android.util.Log, because it honours BuildVars.LOGS_ENABLED.
            error = t.getClass().getSimpleName();
            FileLog.e(t);
        }

        DeviceScanReport deliver;
        if (report != null && report.error == null) {
            persist(report);
            synchronized (reportLock) {
                lastReport = report;
                lastReportLoaded = true;
            }
            deliver = report;
        } else {
            deliver = fallbackReport(report != null ? report
                    : DeviceScanReport.failed(trigger, startedAt, error));
        }
        finish(deliver);
    }

    /** The last good report if there is one; otherwise the failure itself. */
    private DeviceScanReport fallbackReport(DeviceScanReport failure) {
        DeviceScanReport previous;
        if (lastReportLoaded || Looper.myLooper() == Looper.getMainLooper()) {
            previous = lastReport;
        } else {
            previous = loadLastReport();
        }
        return previous != null ? previous : failure;
    }

    private DeviceScanReport scan(int trigger, long startedAt) throws Exception {
        long t0 = SystemClock.elapsedRealtime();
        InstalledAppPolicy policy = ScannerBootstrap.getInstalledAppPolicy();
        if (policy == null) {
            return DeviceScanReport.failed(trigger, startedAt, DeviceScanReport.ERROR_NO_ENGINE);
        }
        ThreatIntel intel = ScannerBootstrap.getThreatIntel();

        // "New since the last scan" is only meaningful against a real
        // previous scan; on the first one nothing is marked new. The same map
        // answers "new or updated": each package's lastUpdateTime as the
        // previous report recorded it. Comparing against the report rather than
        // against DeviceScanCache is deliberate — a Humogram update drops every
        // cached app entry (DeviceScanCache.load), which would otherwise make
        // every app on the phone look new.
        DeviceScanReport previous = lastReportLoaded ? lastReport : loadLastReport();
        Set<String> previousPackages = null;
        Map<String, Long> previousUpdatedAt = null;
        if (previous != null) {
            previousPackages = new HashSet<>();
            previousUpdatedAt = new HashMap<>();
            for (DeviceScanReport.AppResult app : previous.apps) {
                previousPackages.add(app.packageName);
                previousUpdatedAt.put(app.packageName, app.updatedAt);
            }
        }

        final String self = selfStamp();
        final DeviceScanCache cache = DeviceScanCache.load(context, self);
        DeviceAppCollector.CancelSignal cancelSignal = new DeviceAppCollector.CancelSignal() {
            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        };
        DeviceAppCollector collector = new DeviceAppCollector(context, cache, cancelSignal);

        DeviceAppCollector.RuntimeState state = collector.readRuntimeState();
        checkCancelled();
        List<DeviceAppCollector.Candidate> candidates = collector.listPackages();
        checkCancelled();
        List<DeviceAppCollector.ApkFile> files = collector.findApkFiles();
        checkCancelled();

        int total = candidates.size() + files.size();
        int done = 0;
        progress(0, total, null);

        // ---- installed apps ------------------------------------------
        int suspicious = AppRisk.SUSPICIOUS.getLevel();
        List<CheckedApp> checked = new ArrayList<>(candidates.size());
        for (DeviceAppCollector.Candidate c : candidates) {
            checkCancelled();
            try {
                collector.resolveStatic(c);
                progress(done, total, c.displayName());
                collector.resolveRuntime(c, state);

                InstalledAppFacts facts = c.toFacts();
                boolean systemApp = InstalledAppPolicy.isSystemApp(facts);
                if (!systemApp && !c.hasFingerprint() && policy.isSideloaded(facts)
                        && collector.fingerprintApp(c)) {
                    facts = c.toFacts();
                }
                AppAssessment assessment = policy.evaluate(facts);
                // Something already looks wrong: fingerprint it whatever its
                // origin, for the threat-intel hash match.
                if (!c.hasFingerprint() && assessment.getRisk().getLevel() >= suspicious
                        && collector.fingerprintApp(c)) {
                    facts = c.toFacts();
                    assessment = policy.evaluate(facts);
                }
                checked.add(new CheckedApp(c, facts, assessment));
            } catch (DeviceAppCollector.Cancelled e) {
                throw e;
            } catch (PackageManager.NameNotFoundException e) {
                // Uninstalled since the package list was read: nothing to report.
            } catch (Throwable t) {
                // One pathological package must not cost the user the scan.
                // The exception's class only: its message is often the package
                // name, and this log line runs in release builds.
                android.util.Log.w(TAG, "device scan skipped a package: " + t.getClass().getSimpleName());
            }
            done++;
        }

        // ---- APK files -----------------------------------------------
        //
        // evaluateApkFile is the most expensive single call in the scan: it
        // opens the zip, walks the v1 signature blocks and the v2/v3 signing
        // block, parses the binary manifest, and opens the zip again to decide
        // whether it is a bundle. Its answer is a pure function of the file
        // bytes and of the reference data we ship — the threat intel and the
        // brand allowlist, both assets inside our own APK — so it is cached
        // under (path|size|mtime) plus the stamp below. Anything new that
        // evaluateApkFile starts reading must join that stamp, or a stale
        // verdict will outlive its input.
        final String verdictStamp = self + "|" + intel.getVersion();
        List<CheckedFile> checkedFiles = new ArrayList<>(files.size());
        for (DeviceAppCollector.ApkFile apk : files) {
            checkCancelled();
            progress(done, total, apk.file.getName());
            try {
                collector.fingerprintFile(apk);
                DeviceScanCache.FileEntry fp = apk.entry;
                AppAssessment assessment = fp != null ? fp.assessment(verdictStamp) : null;
                if (assessment == null) {
                    assessment = policy.evaluateApkFile(apk.file,
                            fp != null ? fp.sha256 : null,
                            fp != null ? fp.sha1 : null,
                            fp != null ? fp.md5 : null);
                    // Not for a cache-root file: see ApkFile#rootType.
                    if (fp != null && !apk.isCacheRoot()) {
                        fp.rememberAssessment(verdictStamp, assessment);
                    }
                }
                checkedFiles.add(new CheckedFile(apk, assessment));
            } catch (DeviceAppCollector.Cancelled e) {
                throw e;
            } catch (Throwable t) {
                // The class only: an IOException's message is the absolute path.
                android.util.Log.w(TAG, "device scan skipped a file: " + t.getClass().getSimpleName());
            }
            done++;
        }
        progress(total, total, null);

        // ---- report ------------------------------------------------------
        List<DeviceScanReport.AppResult> apps = new ArrayList<>(checked.size());
        int hashed = 0;
        int newOrChanged = 0;
        for (CheckedApp app : checked) {
            DeviceAppCollector.Candidate c = app.candidate;
            if (c.hasFingerprint()) {
                hashed++;
            }
            List<Signal> signals = app.assessment.getSignals();
            if (c.system && signals.isEmpty()) {
                continue;
            }
            if (c.system) {
                // A system app is only listed when something matched; only
                // then is its name worth resolving.
                collector.ensureLabel(c);
            }
            DeviceScanCache.AppEntry entry = c.entry;
            boolean isNew = previousPackages != null && !c.system && !previousPackages.contains(c.packageName);
            if (!c.system && previousUpdatedAt != null) {
                Long was = previousUpdatedAt.get(c.packageName);
                // A previous report written before updatedAt existed stores 0;
                // treat that as "unchanged" rather than counting every app once.
                if (was == null || (was > 0 && was != c.lastUpdateTime)) {
                    newOrChanged++;
                }
            }
            apps.add(new DeviceScanReport.AppResult(
                    c.packageName,
                    entry != null && entry.labelLoaded ? entry.label : null,
                    c.versionName,
                    entry != null ? entry.installer : null,
                    app.assessment.getVerdict().getWire(),
                    app.assessment.getSource(),
                    app.assessment.getFamily(),
                    c.versionCode,
                    c.lastUpdateTime,
                    app.assessment.getRisk().getLevel(),
                    c.system,
                    policy.isStoreInstalled(app.facts),
                    isNew,
                    findingsOf(signals),
                    entry != null ? entry.sha256 : null));
        }

        List<DeviceScanReport.FileResult> fileResults = new ArrayList<>(checkedFiles.size());
        for (CheckedFile file : checkedFiles) {
            fileResults.add(fileResult(file));
        }

        cache.save();
        return new DeviceScanReport(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime() - t0,
                trigger,
                checked.size(),
                hashed,
                newOrChanged,
                intel.getVersion(),
                apps,
                fileResults,
                null);
    }

    /** An APK file's row, exactly as the core judged it. */
    private static DeviceScanReport.FileResult fileResult(CheckedFile file) {
        AppAssessment assessment = file.assessment;
        DeviceAppCollector.ApkFile apk = file.apk;
        DeviceScanCache.FileEntry fp = apk.entry;
        // A cache-root file keeps its path in this in-memory row, so the page's
        // Delete button still works this session, and its row is dropped by
        // DeviceScanReport.toJson rather than written — see FileResult#ephemeral.
        return new DeviceScanReport.FileResult(
                apk.path,
                apk.file.getName(),
                assessment.getPackageName(),
                assessment.getLabel(),
                apk.size,
                apk.modified,
                assessment.getRisk().getLevel(),
                findingsOf(assessment.getSignals()),
                fp != null ? fp.sha256 : null,
                assessment.getVerdict().getWire(),
                assessment.getSource(),
                assessment.getFamily(),
                apk.isCacheRoot());
    }

    private static List<ScanFinding> findingsOf(List<Signal> signals) {
        List<ScanFinding> out = new ArrayList<>(signals.size());
        for (Signal signal : signals) {
            List<String> args = signal.getArgs();
            out.add(new ScanFinding(signal.getCode(), args.toArray(new String[0])));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // listeners
    // ------------------------------------------------------------------

    /**
     * Record progress, and post it to the UI at most every
     * {@link #PROGRESS_INTERVAL_MS} — always the latest values, never a
     * backlog of stale ones: a phone with three hundred apps must not queue
     * three hundred runnables on the main thread.
     */
    private void progress(int done, int total, String label) {
        progressDone = done;
        progressTotal = total;
        progressLabel = label;
        long now = SystemClock.elapsedRealtime();
        if (done != 0 && done != total && now - lastProgressAt < PROGRESS_INTERVAL_MS) {
            return;
        }
        lastProgressAt = now;
        if (progressPosted.compareAndSet(false, true)) {
            AndroidUtilities.runOnUIThread(dispatchProgress);
        }
    }

    private final Runnable dispatchProgress = new Runnable() {
        @Override
        public void run() {
            progressPosted.set(false);
            if (!running) {
                // Finished already; onFinished is on its way.
                return;
            }
            int done = progressDone;
            int total = progressTotal;
            String label = progressLabel;
            for (Listener l : listeners) {
                safeProgress(l, done, total, label);
            }
            List<Listener> once;
            synchronized (lock) {
                once = new ArrayList<>(runListeners);
            }
            for (Listener l : once) {
                if (!listeners.contains(l)) {
                    safeProgress(l, done, total, label);
                }
            }
        }
    };

    /** Give a listener that joins mid-scan the current position straight away. */
    private void postCurrentProgress(final Listener l) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                if (running) {
                    safeProgress(l, progressDone, progressTotal, progressLabel);
                }
            }
        });
    }

    private void finish(final DeviceScanReport report) {
        final List<Listener> once;
        synchronized (lock) {
            running = false;
            cancelled = false;
            once = new ArrayList<>(runListeners);
            runListeners.clear();
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    safeFinished(l, report);
                }
                for (Listener l : once) {
                    if (!listeners.contains(l)) {
                        safeFinished(l, report);
                    }
                }
            }
        });
    }

    // A listener that throws is a bug in that screen; it must not stop the
    // other listeners (the launch hook, the settings row) from hearing the
    // result, so it is logged rather than rethrown.

    private static void safeProgress(Listener l, int done, int total, String label) {
        try {
            l.onProgress(done, total, label);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "device scan listener failed", t);
        }
    }

    private static void safeFinished(Listener l, DeviceScanReport report) {
        try {
            l.onFinished(report);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "device scan listener failed", t);
        }
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    private File reportFile() {
        return new File(DeviceScanCache.dir(context), REPORT_FILE);
    }

    private void persist(DeviceScanReport report) {
        try {
            if (!DeviceScanCache.writeAtomically(reportFile(), report.toJson().toString())) {
                android.util.Log.w(TAG, "device scan report not saved");
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "device scan report not saved: " + t);
        }
    }

    /** Read report.json once per process. Off the main thread only. */
    private DeviceScanReport loadLastReport() {
        synchronized (reportLock) {
            if (lastReportLoaded) {
                return lastReport;
            }
            DeviceScanReport loaded = null;
            try {
                String text = DeviceScanCache.readText(reportFile());
                if (text != null) {
                    loaded = DeviceScanReport.fromJson(new JSONObject(text));
                }
            } catch (Throwable t) {
                // Corrupt: as if there had never been a scan. The next one
                // writes a good file.
                loaded = null;
            }
            if (lastReport == null) {
                lastReport = loaded;
            }
            lastReportLoaded = true;
            return lastReport;
        }
    }

    /**
     * Identifies this build of Humogram for {@link DeviceScanCache}: version
     * code plus install time, so an update — which can change the manifest's
     * {@code <queries>} and what the collector reads — invalidates the app
     * entries.
     */
    @SuppressWarnings("deprecation")
    private String selfStamp() {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo self;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                self = pm.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                self = pm.getPackageInfo(context.getPackageName(), 0);
            }
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? self.getLongVersionCode() : self.versionCode;
            return versionCode + ":" + self.lastUpdateTime;
        } catch (Throwable t) {
            return "0";
        }
    }

    private void checkCancelled() {
        if (cancelled) {
            throw new DeviceAppCollector.Cancelled();
        }
    }

    // ------------------------------------------------------------------
    // per-run bookkeeping
    // ------------------------------------------------------------------

    private static final class CheckedApp {
        final DeviceAppCollector.Candidate candidate;
        final InstalledAppFacts facts;
        final AppAssessment assessment;

        CheckedApp(DeviceAppCollector.Candidate candidate, InstalledAppFacts facts, AppAssessment assessment) {
            this.candidate = candidate;
            this.facts = facts;
            this.assessment = assessment;
        }
    }

    private static final class CheckedFile {
        final DeviceAppCollector.ApkFile apk;
        final AppAssessment assessment;

        CheckedFile(DeviceAppCollector.ApkFile apk, AppAssessment assessment) {
            this.apk = apk;
            this.assessment = assessment;
        }
    }
}
