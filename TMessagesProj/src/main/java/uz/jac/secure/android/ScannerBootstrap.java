package uz.jac.secure.android;

import android.content.Context;
import android.content.res.AssetManager;

import org.telegram.messenger.UserConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import uz.jac.secure.core.device.InstalledAppPolicy;
import uz.jac.secure.core.device.ThreatIntel;
import uz.jac.secure.core.engine.EngineFactory;

/**
 * One-line entry point from {@code ApplicationLoader}.
 *
 * <pre>ScannerBootstrap.install(this);</pre>
 *
 * Everything the scanner needs to exist is assembled here rather than in
 * ApplicationLoader, and that is the same rule the download hook follows: the
 * diff against upstream stays one line, so rebasing onto a new Telegram
 * release does not mean re-reading a page of our initialisation logic wedged
 * into the middle of theirs.
 *
 * <h3>Failing to start must not break the messenger</h3>
 *
 * If the reference data will not parse — a corrupt asset, a bad merge — the
 * right outcome is an app with no scanner, not an app that will not launch.
 * A messenger that fails to start is a total loss for the user; a messenger
 * without a scanner is the messenger they had before we shipped. So install()
 * swallows everything and records that it failed, and {@link #isInstalled}
 * lets the UI ask.
 *
 * <h3>The device scanner's half</h3>
 *
 * The parsed reference data is kept ({@link #getReferenceData}) because the
 * device virus scanner judges installed apps against the same brand
 * allowlist the file engines use — a counterfeit bank app must be called the
 * same thing in a chat and on the home screen. Its own inputs, the bundled
 * known-malware indicators ({@code device-threat-intel.json}) and the
 * {@link InstalledAppPolicy} built from them, are loaded lazily on first use
 * rather than here: nothing on the launch path needs them, and
 * {@link DeviceScanner} warms them up on its own background thread — but only
 * once something creates it, which install() deliberately does not do. A
 * missing or corrupt intel file falls back to
 * {@link ThreatIntel#empty()} — heuristics still run — and is logged; it
 * never takes the file scanner down with it.
 *
 * <h3>The link guard's half</h3>
 *
 * {@link LinkGuard} is started from here as well, from the same reference
 * data: the brand domains it trusts, and the look-alike checks it runs, are
 * the ones the file scanner already parsed. It is installed last and inside
 * its own guard, for the reason above turned one notch further — a link
 * guard that cannot start must not cost the user the file scanner either.
 */
public final class ScannerBootstrap {

    /** Bundled known-malware indicators; see shared/data/device-threat-intel.json. */
    private static final String DEVICE_INTEL_ASSET = "device-threat-intel.json";

    private static volatile boolean installed;
    private static volatile String failure;

    private static volatile Context appContext;
    private static volatile EngineFactory.ReferenceData referenceData;

    private static final Object deviceLock = new Object();
    private static volatile boolean deviceLoaded;
    private static volatile ThreatIntel threatIntel;
    private static volatile InstalledAppPolicy installedAppPolicy;

    private ScannerBootstrap() {
    }

    public static void install(Context context) {
        try {
            Context app = context.getApplicationContext();
            appContext = app != null ? app : context;
            final Context cleanupContext = appContext;
            org.telegram.messenger.Utilities.globalQueue.postRunnable(
                    () -> HumogramConfig.cleanUpRemovedFeatures(cleanupContext));

            AssetManager assets = context.getAssets();
            // Parsed once for every account and every mode: the brand index and
            // the magic-byte table cost real time to build and depend on
            // neither.
            EngineFactory.ReferenceData data = EngineFactory.prepare(
                    asset(assets, "magic-bytes.json"),
                    asset(assets, "psl-subset.txt"),
                    asset(assets, "uz-brand-allowlist.json"));
            referenceData = data;

            // Local-only, by decision, not by accident. No base URL and no
            // token source are handed to the factory, which makes the backend
            // branch in EngineFactory unreachable: no device registration, no
            // /v1/verdict lookup, no hash of any file ever leaving the phone.
            // Every verdict below comes from the on-device analysers alone.
            // The consequence is accepted product behaviour: the local policy
            // never says CLEAN about an installer, so an APK stays red for
            // good and installing one always takes the deliberate two-step
            // override. The device scanner is local-only in the same way: it
            // has no network step at all.

            // One gate per account. Telegram supports several signed-in
            // accounts at once, and a verdict cache shared between them would
            // leak the fact that a file was seen on one into the other.
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                ScanGate.install(account, context, mode ->
                        EngineFactory.engineFor(data, mode));
                LinkGate.install(account, mode ->
                        EngineFactory.linkScannerFor(data, mode));
            }

            installed = true;

            // The link guard, last and on its own. It is given the same
            // reference data and, like everything above, no backend: it
            // only ever downloads the owner's signed site list, and no link
            // leaves the phone. install() does not throw, but loading the
            // class can (a core jar without the link package), and that
            // must not reach the catch below and mark the file scanner —
            // which is up by now — as failed.
            try {
                LinkGuard.install(appContext, data);
            } catch (Throwable t) {
                android.util.Log.e("jac", "link guard failed to start: " + t.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            // Deliberately broad. Whatever went wrong, the messenger keeps
            // working — see the class comment.
            installed = false;
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            android.util.Log.e("jac", "scanner failed to start: " + failure, t);
        }

        // Deliberately no DeviceScanner here. Creating it queues the warm-up
        // that parses the ~500 KB threat intel and holds it for the life of the
        // process, and install() runs at every process start — including one a
        // push woke only to post a notification, one with nobody signed in, one
        // sitting behind the passcode, and one where the user turned launch
        // scans off. The scanner is created by whoever first needs it:
        // DeviceScanLaunch when its gates pass, the scanner section of the
        // Humogram page, or the Settings card through DeviceScanUi.
    }

    public static boolean isInstalled() {
        return installed;
    }

    /** Why the scanner is not running, or null. For the settings screen. */
    public static String getFailure() {
        return failure;
    }

    /** The parsed brand allowlist, magic bytes and PSL shared by every engine; null if install failed. */
    public static EngineFactory.ReferenceData getReferenceData() {
        return referenceData;
    }

    /**
     * The device scanner's policy, or null when the scanner could not start
     * (no reference data). Loads the threat intel on first call — which is
     * disk I/O, so call it from a worker; {@link DeviceScanner} does so on its
     * own thread as soon as something creates it, and the result is cached for
     * the life of the process.
     */
    public static InstalledAppPolicy getInstalledAppPolicy() {
        ensureDeviceEngine();
        return installedAppPolicy;
    }

    /**
     * The bundled threat intel; {@link ThreatIntel#empty()} when missing,
     * corrupt or not loaded. Never null.
     *
     * <p>The shipped file is ~500 KB and takes a noticeable fraction of a
     * second to parse on a phone. Whoever calls first pays for the parse (or
     * waits for the call that is already doing it): normally
     * {@link DeviceScanner}'s warm-up, on its own thread. Never call it from the
     * main thread. UI code that only wants the version string should read
     * {@code DeviceScanReport.intelVersion} instead.
     */
    public static ThreatIntel getThreatIntel() {
        ensureDeviceEngine();
        ThreatIntel intel = threatIntel;
        return intel != null ? intel : ThreatIntel.empty();
    }

    private static void ensureDeviceEngine() {
        if (deviceLoaded) {
            return;
        }
        synchronized (deviceLock) {
            if (deviceLoaded) {
                return;
            }
            Context context = appContext;
            EngineFactory.ReferenceData data = referenceData;
            if (context == null || data == null) {
                // Not installed (yet, or at all). Not marked loaded, so a
                // later successful install() can still load it.
                return;
            }
            ThreatIntel intel;
            try {
                intel = ThreatIntel.parse(asset(context.getAssets(), DEVICE_INTEL_ASSET));
            } catch (Throwable t) {
                // Missing or unparseable: heuristics still run, known-malware
                // matching does not. Loud in the log, silent for the user.
                android.util.Log.e("jac", "device threat intel unavailable, using none: " + t, t);
                intel = ThreatIntel.empty();
            }
            threatIntel = intel;
            try {
                installedAppPolicy = EngineFactory.installedAppPolicyFor(data, intel);
            } catch (Throwable t) {
                android.util.Log.e("jac", "device scanner policy failed to build", t);
                installedAppPolicy = null;
            }
            deviceLoaded = true;
        }
    }

    /** One bundled asset as UTF-8 text. Package-private: {@link LinkGuard} reads its bundled list with it. */
    static String asset(AssetManager assets, String name) throws Exception {
        InputStream stream = assets.open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            stream.close();
        }
    }

}
