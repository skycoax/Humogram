package uz.jac.secure.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import uz.jac.secure.core.device.AppRisk;

/**
 * What one run of the device virus scanner found, frozen.
 *
 * <p>Immutable, and deliberately made of plain Java values — strings, ints,
 * {@link ScanFinding}s — rather than the core's Kotlin types. It is the one
 * object that crosses from the scanner thread to the UI, and it is written to
 * disk so the settings page can show "last checked at…" in a process that has
 * not scanned yet. A report that held {@code InstalledAppFacts} or
 * {@code AppAssessment} would tie the file format to whatever those classes
 * look like in the core build that happens to be installed; this one ties it
 * to nothing but the field names below, which {@link #fromJson} reads
 * tolerantly (a field it does not know is ignored, a field it cannot find
 * takes its default). Reports written while the scanner still had a cloud
 * mode carry {@code mode} and {@code networkStatus} fields; they are ignored
 * on read like any other unknown field, and the next scan writes a report
 * without them.
 *
 * <h3>What the numbers mean</h3>
 *
 * <ul>
 *   <li>{@link #appsChecked} — installed packages this scan looked at (what
 *       the package-visibility rules let us see, minus Humogram itself).</li>
 *   <li>{@link #appsHashed} — of those, how many have an APK fingerprint in
 *       this report, whether it was read now or reused from the cache for an
 *       unchanged app. Store installs and system apps are not fingerprinted
 *       unless something about them looks wrong; see {@link DeviceScanner}.</li>
 *   <li>{@link #newOrChanged} — non-system packages that are new since the
 *       <em>previous report</em>, or whose install was updated since it. The
 *       launch Bulletin uses it to say "checked N new or updated apps", so it
 *       must count changes on the phone and nothing else: it is deliberately
 *       not derived from {@link DeviceScanCache} misses, which a Humogram
 *       update wipes wholesale (see {@link DeviceScanCache#load}) and which
 *       would then report every app on the phone as new. 0 on the first scan,
 *       when there is nothing to compare against.</li>
 * </ul>
 *
 * <h3>What "threat" means</h3>
 *
 * {@link #threatCount} and {@link #worstRisk} take a {@link Context} because
 * trust is not part of the report. The user can trust or untrust an app on
 * the page long after the scan finished, and the count must follow at once
 * without a rescan — so it is asked of {@link HumogramConfig} at read time.
 */
public final class DeviceScanReport {

    /** {@link #error} of a scan that {@link DeviceScanner#cancel()} stopped. */
    public static final String ERROR_CANCELLED = "cancelled";
    /** {@link #error} when the engine never started (see {@link ScannerBootstrap#getFailure()}). */
    public static final String ERROR_NO_ENGINE = "no_engine";

    private static final int FORMAT_VERSION = 1;

    public final long finishedAt;
    public final long durationMs;
    public final int trigger;
    public final int appsChecked;
    public final int appsHashed;
    public final int newOrChanged;
    /** Version string of the bundled threat intel this scan matched against; never null. */
    public final String intelVersion;
    /** All non-system visible apps, plus any system app with a finding; risk desc, then label. */
    public final List<AppResult> apps;
    /** APK files in Humogram's own download folders; risk desc, then name. */
    public final List<FileResult> apkFiles;
    /**
     * Null when the scan ran to the end. Otherwise why it did not —
     * {@link #ERROR_CANCELLED}, {@link #ERROR_NO_ENGINE}, or an exception
     * summary — and the lists hold only what is known to be true (usually
     * nothing: see {@link DeviceScanner} for which report a failed run
     * delivers).
     */
    public final String error;

    public DeviceScanReport(long finishedAt, long durationMs, int trigger,
                            int appsChecked, int appsHashed, int newOrChanged, String intelVersion,
                            List<AppResult> apps, List<FileResult> apkFiles, String error) {
        this.finishedAt = finishedAt;
        this.durationMs = durationMs;
        this.trigger = trigger;
        this.appsChecked = appsChecked;
        this.appsHashed = appsHashed;
        this.newOrChanged = newOrChanged;
        this.intelVersion = intelVersion != null ? intelVersion : "";
        this.apps = sortedApps(apps);
        this.apkFiles = sortedFiles(apkFiles);
        this.error = error;
    }

    /** An empty report that says only why there is nothing in it. */
    static DeviceScanReport failed(int trigger, long startedAt, String error) {
        long now = System.currentTimeMillis();
        return new DeviceScanReport(now, Math.max(0L, now - startedAt), trigger,
                0, 0, 0, "", null, null, error != null ? error : "failed");
    }

    /**
     * Untrusted apps with risk ≥ SUSPICIOUS, plus APK files with risk ≥
     * SUSPICIOUS. NOTICE-level items (a sideloaded app with nothing else
     * against it) are listed on the page but are not threats.
     */
    public int threatCount(Context c) {
        int threshold = AppRisk.SUSPICIOUS.getLevel();
        int count = 0;
        for (int i = 0; i < apps.size(); i++) {
            AppResult app = apps.get(i);
            if (app.risk >= threshold && !HumogramConfig.isAppTrusted(c, app.trustKey())) {
                count++;
            }
        }
        for (int i = 0; i < apkFiles.size(); i++) {
            if (apkFiles.get(i).risk >= threshold) {
                count++;
            }
        }
        return count;
    }

    /** The {@code AppRisk} level of the worst untrusted app or APK file; {@code NONE}'s level when there is none. */
    public int worstRisk(Context c) {
        int worst = AppRisk.NONE.getLevel();
        for (int i = 0; i < apps.size(); i++) {
            AppResult app = apps.get(i);
            if (app.risk > worst && !HumogramConfig.isAppTrusted(c, app.trustKey())) {
                worst = app.risk;
            }
        }
        for (int i = 0; i < apkFiles.size(); i++) {
            worst = Math.max(worst, apkFiles.get(i).risk);
        }
        return worst;
    }

    /** The result for one package, or null. */
    public AppResult findApp(String packageName) {
        if (packageName == null) {
            return null;
        }
        for (int i = 0; i < apps.size(); i++) {
            if (packageName.equals(apps.get(i).packageName)) {
                return apps.get(i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("format", FORMAT_VERSION);
            o.put("finishedAt", finishedAt);
            o.put("durationMs", durationMs);
            o.put("trigger", trigger);
            o.put("appsChecked", appsChecked);
            o.put("appsHashed", appsHashed);
            o.put("newOrChanged", newOrChanged);
            o.put("intelVersion", intelVersion);
            if (error != null) {
                o.put("error", error);
            }
            JSONArray appArray = new JSONArray();
            for (int i = 0; i < apps.size(); i++) {
                appArray.put(apps.get(i).toJson());
            }
            o.put("apps", appArray);
            JSONArray fileArray = new JSONArray();
            for (int i = 0; i < apkFiles.size(); i++) {
                FileResult file = apkFiles.get(i);
                if (file.ephemeral) {
                    // A file under the cache root, where upstream keeps
                    // secret-chat documents. It is shown for this session and
                    // then forgotten: neither its path nor its hash may survive
                    // the chat it came from. See FileResult#ephemeral.
                    continue;
                }
                fileArray.put(file.toJson());
            }
            o.put("apkFiles", fileArray);
        } catch (JSONException ignored) {
            // Only thrown for non-finite doubles, and there are none here.
        }
        return o;
    }

    /**
     * Rebuild a report written by {@link #toJson}. Never throws on odd
     * content; returns null only for a null object. A row without a package
     * name (or a file without a path) is dropped rather than shown blank.
     */
    public static DeviceScanReport fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        List<AppResult> apps = new ArrayList<>();
        JSONArray appArray = o.optJSONArray("apps");
        if (appArray != null) {
            for (int i = 0; i < appArray.length(); i++) {
                AppResult app = AppResult.fromJson(appArray.optJSONObject(i));
                if (app != null) {
                    apps.add(app);
                }
            }
        }
        List<FileResult> files = new ArrayList<>();
        JSONArray fileArray = o.optJSONArray("apkFiles");
        if (fileArray != null) {
            for (int i = 0; i < fileArray.length(); i++) {
                FileResult file = FileResult.fromJson(fileArray.optJSONObject(i));
                if (file != null) {
                    files.add(file);
                }
            }
        }
        return new DeviceScanReport(
                o.optLong("finishedAt", 0L),
                o.optLong("durationMs", 0L),
                o.optInt("trigger", DeviceScanner.TRIGGER_LAUNCH),
                o.optInt("appsChecked", 0),
                o.optInt("appsHashed", 0),
                o.optInt("newOrChanged", 0),
                optString(o, "intelVersion"),
                apps,
                files,
                optString(o, "error"));
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /** One installed package. */
    public static final class AppResult {
        public final String packageName;
        /** The name the launcher shows; the package name when the app has none (which is itself a finding). */
        public final String label;
        public final String versionName;
        /** Installing package as the platform reported it; null = unknown, adb, or not visible to us. */
        public final String installer;
        /** {@code Verdict.wire}: clean / suspicious / malicious / unknown. */
        public final String verdict;
        /** Who decided: {@code local_intel}, {@code local_heuristics}, or {@code none}. */
        public final String source;
        /** Malware family name when threat intel matched; otherwise null. */
        public final String family;
        public final long versionCode;
        /**
         * {@code PackageInfo.lastUpdateTime}; 0 when unknown (including in a
         * report written before this field existed). The next scan compares it
         * with what it reads now to count {@link #newOrChanged} — the one
         * comparison that survives a cache wipe.
         */
        public final long updatedAt;
        /** {@code AppRisk.level}. */
        public final int risk;
        public final boolean system;
        public final boolean store;
        public final boolean newSinceLastScan;
        /** Ordered: decisive first, then by severity — so {@code findings.get(0)} is the lead reason. */
        public final List<ScanFinding> findings;
        /** Lowercase hex SHA-256 of base.apk when it was fingerprinted; otherwise null. */
        public final String sha256;

        public AppResult(String packageName, String label, String versionName, String installer,
                         String verdict, String source, String family, long versionCode, long updatedAt,
                         int risk, boolean system, boolean store, boolean newSinceLastScan,
                         List<ScanFinding> findings, String sha256) {
            this.packageName = packageName;
            this.label = label != null && label.trim().length() > 0 ? label : packageName;
            this.versionName = versionName;
            this.installer = installer;
            this.verdict = verdict != null ? verdict : "unknown";
            this.source = source != null ? source : "none";
            this.family = family;
            this.versionCode = versionCode;
            this.updatedAt = updatedAt;
            this.risk = risk;
            this.system = system;
            this.store = store;
            this.newSinceLastScan = newSinceLastScan;
            this.findings = immutable(findings);
            this.sha256 = sha256;
        }

        /**
         * {@code packageName + "@" + versionCode} — the key trust and
         * once-only notices are stored under. Version-bound on purpose; see
         * {@link HumogramConfig#isAppTrusted}.
         */
        public String trustKey() {
            return packageName + "@" + versionCode;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("pkg", packageName);
                o.put("label", label);
                putOpt(o, "versionName", versionName);
                putOpt(o, "installer", installer);
                o.put("verdict", verdict);
                o.put("source", source);
                putOpt(o, "family", family);
                o.put("versionCode", versionCode);
                if (updatedAt > 0) {
                    o.put("updatedAt", updatedAt);
                }
                o.put("risk", risk);
                o.put("system", system);
                o.put("store", store);
                o.put("new", newSinceLastScan);
                o.put("findings", findingsToJson(findings));
                putOpt(o, "sha256", sha256);
            } catch (JSONException ignored) {
            }
            return o;
        }

        static AppResult fromJson(JSONObject o) {
            if (o == null) {
                return null;
            }
            String pkg = optString(o, "pkg");
            if (pkg == null || pkg.isEmpty()) {
                return null;
            }
            return new AppResult(pkg,
                    optString(o, "label"),
                    optString(o, "versionName"),
                    optString(o, "installer"),
                    optString(o, "verdict"),
                    optString(o, "source"),
                    optString(o, "family"),
                    o.optLong("versionCode", 0L),
                    o.optLong("updatedAt", 0L),
                    o.optInt("risk", 0),
                    o.optBoolean("system", false),
                    o.optBoolean("store", false),
                    o.optBoolean("new", false),
                    findingsFromJson(o.optJSONArray("findings")),
                    optString(o, "sha256"));
        }

        @Override
        public String toString() {
            return packageName + " risk=" + risk + " " + findings;
        }
    }

    /** One APK file found in Humogram's own download or cache folders. */
    public static final class FileResult {
        public final String path;
        public final String name;
        /** Package the APK would install, when its manifest could be read; otherwise null. */
        public final String packageName;
        /** The APK's own label when readable; otherwise the file name. Never null. */
        public final String label;
        public final long size;
        /**
         * {@code File.lastModified()} as the scan saw it; 0 when unknown.
         * Together with {@link #size} it is what "Delete" re-checks before it
         * removes anything, so a row from an older report cannot delete a
         * different file that has since taken the same name.
         */
        public final long mtime;
        /** {@code AppRisk.level}. */
        public final int risk;
        /** Ordered like {@link AppResult#findings}. */
        public final List<ScanFinding> findings;
        /** Lowercase hex SHA-256 of the file; null if it could not be read. */
        public final String sha256;
        /** {@code Verdict.wire}. */
        public final String verdict;
        public final String source;
        public final String family;
        /**
         * This row exists for this session only: it describes a file under
         * Telegram's cache root, where upstream stores secret-chat documents
         * decrypted ({@code FileLoader}: {@code if (document.key != null) type =
         * MEDIA_DIR_CACHE}). Such a file is checked on this phone like any
         * other and can be deleted from the page, but nothing derived from it
         * is written to {@code report.json} or {@code cache.json}, or
         * remembered in preferences — so it cannot
         * outlive the chat that self-destructs. Always false for a row read
         * back from disk, because such a row is never written.
         */
        public final boolean ephemeral;

        public FileResult(String path, String name, String packageName, String label, long size, long mtime,
                          int risk, List<ScanFinding> findings, String sha256, String verdict, String source,
                          String family, boolean ephemeral) {
            this.path = path;
            this.name = name != null ? name : "";
            this.packageName = packageName;
            this.label = label != null && label.trim().length() > 0 ? label : this.name;
            this.size = size;
            this.mtime = mtime;
            this.risk = risk;
            this.findings = immutable(findings);
            this.sha256 = sha256;
            this.verdict = verdict != null ? verdict : "unknown";
            this.source = source != null ? source : "none";
            this.family = family;
            this.ephemeral = ephemeral;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("path", path);
                o.put("name", name);
                putOpt(o, "pkg", packageName);
                o.put("label", label);
                o.put("size", size);
                if (mtime > 0) {
                    o.put("mtime", mtime);
                }
                o.put("risk", risk);
                o.put("findings", findingsToJson(findings));
                putOpt(o, "sha256", sha256);
                o.put("verdict", verdict);
                o.put("source", source);
                putOpt(o, "family", family);
            } catch (JSONException ignored) {
            }
            return o;
        }

        static FileResult fromJson(JSONObject o) {
            if (o == null) {
                return null;
            }
            String path = optString(o, "path");
            if (path == null || path.isEmpty()) {
                return null;
            }
            return new FileResult(path,
                    optString(o, "name"),
                    optString(o, "pkg"),
                    optString(o, "label"),
                    o.optLong("size", 0L),
                    o.optLong("mtime", 0L),
                    o.optInt("risk", 0),
                    findingsFromJson(o.optJSONArray("findings")),
                    optString(o, "sha256"),
                    optString(o, "verdict"),
                    optString(o, "source"),
                    optString(o, "family"),
                    false);
        }

        @Override
        public String toString() {
            return name + " risk=" + risk + " " + findings;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static final Comparator<AppResult> APP_ORDER = new Comparator<AppResult>() {
        @Override
        public int compare(AppResult a, AppResult b) {
            if (a.risk != b.risk) {
                return a.risk > b.risk ? -1 : 1;
            }
            int byLabel = String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
            return byLabel != 0 ? byLabel : a.packageName.compareTo(b.packageName);
        }
    };

    private static final Comparator<FileResult> FILE_ORDER = new Comparator<FileResult>() {
        @Override
        public int compare(FileResult a, FileResult b) {
            if (a.risk != b.risk) {
                return a.risk > b.risk ? -1 : 1;
            }
            int byName = String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
            return byName != 0 ? byName : a.path.compareTo(b.path);
        }
    };

    private static List<AppResult> sortedApps(List<AppResult> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        List<AppResult> copy = new ArrayList<>(in);
        Collections.sort(copy, APP_ORDER);
        return Collections.unmodifiableList(copy);
    }

    private static List<FileResult> sortedFiles(List<FileResult> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        List<FileResult> copy = new ArrayList<>(in);
        Collections.sort(copy, FILE_ORDER);
        return Collections.unmodifiableList(copy);
    }

    private static List<ScanFinding> immutable(List<ScanFinding> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(in));
    }

    private static JSONArray findingsToJson(List<ScanFinding> findings) throws JSONException {
        JSONArray array = new JSONArray();
        for (int i = 0; i < findings.size(); i++) {
            ScanFinding finding = findings.get(i);
            JSONObject f = new JSONObject();
            f.put("code", finding.code);
            if (finding.args.length > 0) {
                JSONArray args = new JSONArray();
                for (String arg : finding.args) {
                    args.put(arg != null ? arg : "");
                }
                f.put("args", args);
            }
            array.put(f);
        }
        return array;
    }

    private static List<ScanFinding> findingsFromJson(JSONArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        List<ScanFinding> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject f = array.optJSONObject(i);
            String code = f != null ? optString(f, "code") : null;
            if (code == null || code.isEmpty()) {
                continue;
            }
            JSONArray argArray = f.optJSONArray("args");
            String[] args = new String[argArray != null ? argArray.length() : 0];
            for (int j = 0; j < args.length; j++) {
                args[j] = argArray.optString(j, "");
            }
            out.add(new ScanFinding(code, args));
        }
        return out;
    }

    /** {@code optString} that keeps null as null (org.json turns it into "null" or ""). */
    static String optString(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) {
            return null;
        }
        Object value = o.opt(key);
        return value != null ? String.valueOf(value) : null;
    }

    private static void putOpt(JSONObject o, String key, Object value) throws JSONException {
        if (value != null) {
            o.put(key, value);
        }
    }
}
