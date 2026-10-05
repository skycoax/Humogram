package uz.jac.secure.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import uz.jac.secure.core.device.AppAssessment;
import uz.jac.secure.core.device.AppRisk;
import uz.jac.secure.core.model.Severity;
import uz.jac.secure.core.model.Signal;
import uz.jac.secure.core.model.Verdict;

/**
 * What the device scanner already knows about this phone, so the next launch
 * does not have to learn it again.
 *
 * <h3>Why a scan at every launch is affordable</h3>
 *
 * Reading an app's manifest details through PackageManager costs a binder
 * round trip with a large reply, and fingerprinting its APK costs a read of
 * the whole file — tens of megabytes, seconds on a budget phone's eMMC. Doing
 * either for every app at every launch would make the scanner the most
 * expensive thing Humogram does. But almost nothing on a phone changes
 * between two launches, and the platform already tells us cheaply when
 * something did: {@code getInstalledPackages(0)} returns version code, last
 * update time and APK path for every visible package in one call. So each app
 * is filed under
 *
 * <pre>packageName | versionCode | lastUpdateTime | sourceDir | apkSize</pre>
 *
 * and while that key is unchanged its static facts (requested permissions,
 * declared services and receivers, signing certificates, label, installer)
 * and its APK fingerprint are reused as they are. {@code sourceDir} is in the
 * key because Android 11+ moves every update to a fresh randomised directory,
 * and the size because a same-version reinstall of a different build is
 * exactly what a repackaged banker looks like. What can change without an
 * update — enabled accessibility services, granted SMS permissions, the SMS
 * role, whether the icon is hidden — is never stored here; the collector
 * re-reads it at every scan.
 *
 * <h3>What else is kept</h3>
 *
 * Fingerprints of loose APK files, under {@code path|size|mtime}, and with
 * them the verdict {@code evaluateApkFile} gave the file — stamped with our
 * build and the threat-intel version, so it is reused only against the same
 * reference data ({@link FileEntry#assessment}).
 *
 * <p>Nothing here is ever stored for a file found under Telegram's cache root:
 * that is where upstream keeps secret-chat documents, and neither their path
 * nor their hash may outlive the scan. See
 * {@code DeviceAppCollector.ApkFile#rootType}.
 *
 * <h3>Where and how</h3>
 *
 * {@code getNoBackupFilesDir()/jac-devscan/cache.json}: app-private and out of
 * Android's cloud backup, because an inventory of installed apps is personal
 * data (Play says so explicitly) and has no business being restored onto the
 * user's next phone. Written to a temporary file and renamed over the old one,
 * so a crash mid-write leaves the previous cache rather than half a file. A
 * cache that fails to parse is simply empty: the cost of losing it is one
 * slower scan, never a wrong verdict.
 *
 * <p>Entries this scan did not touch are dropped on {@link #save}, so an
 * uninstalled app does not linger in the file. Not thread-safe; it belongs to
 * the single scanner thread.
 */
final class DeviceScanCache {

    static final String DIR_NAME = "jac-devscan";
    private static final String FILE_NAME = "cache.json";
    /**
     * Bumped whenever the meaning of a stored field changes. A cache written
     * by a different format is discarded whole rather than half-trusted.
     *
     * <p>2: file entries also carry the APK-file assessment (see
     * {@link FileEntry#assessment}).
     */
    private static final int FORMAT_VERSION = 2;

    private static final String PERMISSION_PREFIX = "android.permission.";

    /** Static facts about one installed package, valid while {@link #key} is. */
    static final class AppEntry {
        final String packageName;
        final String key;
        /**
         * {@code FULL}: permissions, components, certificates and label were
         * read. {@code LIGHT}: certificates only — what a system app needs,
         * since only threat intel is evaluated for those.
         */
        int level;
        /** False when a part of the deep read failed; such an entry is used once and not saved. */
        boolean complete = true;
        String label;
        /** Locale the label was resolved in; a different current locale means "reload the label". */
        String labelLocale;
        boolean labelLoaded;
        Set<String> requestedPermissions = Collections.emptySet();
        boolean declaresAccessibilityService;
        boolean declaresNotificationListener;
        boolean declaresDeviceAdmin;
        boolean declaresSmsReceiver;
        Set<String> certSha256 = Collections.emptySet();
        String installer;
        int packageSource = -1;
        String sha256;
        String sha1;
        String md5;

        static final int LIGHT = 1;
        static final int FULL = 2;

        AppEntry(String packageName, String key) {
            this.packageName = packageName;
            this.key = key;
        }

        boolean hasFingerprint() {
            return sha256 != null;
        }
    }

    /** The fingerprint of one loose APK file, valid while {@link #key} is. */
    static final class FileEntry {
        final String path;
        final String key;
        String sha256;
        String sha1;
        String md5;
        /**
         * The verdict {@code InstalledAppPolicy.evaluateApkFile} gave this file,
         * and the stamp of the reference data it gave it against — our build
         * plus the threat-intel version. Kept because that call is the scan's
         * most expensive single step (zip open, signature blocks, binary
         * manifest, bundle check) and its answer depends on nothing else. The
         * stamp is what makes reuse safe: a new build or new intel makes every
         * stored verdict miss.
         */
        private String verdictStamp;
        private AppAssessment storedAssessment;

        FileEntry(String path, String key) {
            this.path = path;
            this.key = key;
        }

        /** The stored assessment if it was made against {@code stamp}; otherwise null. */
        AppAssessment assessment(String stamp) {
            return stamp != null && stamp.equals(verdictStamp) ? storedAssessment : null;
        }

        void rememberAssessment(String stamp, AppAssessment value) {
            verdictStamp = stamp;
            storedAssessment = value;
        }
    }

    private final File file;
    private final String selfStamp;
    private final Map<String, AppEntry> apps = new HashMap<>();
    private final Map<String, FileEntry> files = new HashMap<>();
    private final Set<String> touchedApps = new HashSet<>();
    private final Set<String> touchedFiles = new HashSet<>();
    /** The file as read, so an unchanged cache is not rewritten at every launch. */
    private String loadedText;

    private DeviceScanCache(File file, String selfStamp) {
        this.file = file;
        this.selfStamp = selfStamp;
    }

    /** The directory both the cache and the last report live in. */
    static File dir(Context context) {
        return new File(context.getNoBackupFilesDir(), DIR_NAME);
    }

    /**
     * Read the cache, or start an empty one. Never throws.
     *
     * @param selfStamp identifies this build of Humogram (version code and
     *                  install time). App entries written by a different
     *                  build are dropped: an update may have added
     *                  {@code <queries>} entries that make installers visible
     *                  that were null before, or changed what the collector
     *                  reads, and neither may be hidden behind a stale entry.
     *                  File fingerprints do not depend on our build and are
     *                  kept; a file's stored <em>verdict</em>
     *                  does — the threat intel and the brand allowlist it was
     *                  judged against are assets in our APK — and carries its
     *                  own stamp, so it simply misses after an update.
     */
    static DeviceScanCache load(Context context, String selfStamp) {
        DeviceScanCache cache = new DeviceScanCache(new File(dir(context), FILE_NAME), selfStamp);
        try {
            String text = readText(cache.file);
            if (text != null) {
                cache.parse(new JSONObject(text));
                cache.loadedText = text;
            }
        } catch (Throwable t) {
            // Corrupt or unreadable: start over. Losing the cache costs one
            // slower scan and nothing else.
            cache.apps.clear();
            cache.files.clear();
        }
        return cache;
    }

    // ------------------------------------------------------------------
    // apps
    // ------------------------------------------------------------------

    /** The cached entry for this exact key, or null on a miss. Marks it as still in use. */
    AppEntry getApp(String packageName, String key) {
        AppEntry entry = apps.get(packageName);
        if (entry == null || !entry.key.equals(key)) {
            return null;
        }
        touchedApps.add(packageName);
        return entry;
    }

    void putApp(AppEntry entry) {
        apps.put(entry.packageName, entry);
        touchedApps.add(entry.packageName);
    }

    // ------------------------------------------------------------------
    // files
    // ------------------------------------------------------------------

    FileEntry getFile(String path, String key) {
        FileEntry entry = files.get(path);
        if (entry == null || !entry.key.equals(key)) {
            return null;
        }
        touchedFiles.add(path);
        return entry;
    }

    void putFile(FileEntry entry) {
        files.put(entry.path, entry);
        touchedFiles.add(entry.path);
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    /** Write what this scan used, atomically. Returns false (and keeps the old file) on failure. */
    boolean save() {
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT_VERSION);
            root.put("self", selfStamp);

            // Keys in sorted order, so the same content always serialises to
            // the same text and an unchanged cache can be recognised.
            JSONObject appsJson = new JSONObject();
            for (String pkg : sorted(touchedApps)) {
                AppEntry entry = apps.get(pkg);
                if (entry != null && entry.complete) {
                    appsJson.put(pkg, appToJson(entry));
                }
            }
            root.put("apps", appsJson);

            JSONObject filesJson = new JSONObject();
            for (String path : sorted(touchedFiles)) {
                FileEntry entry = files.get(path);
                if (entry != null) {
                    filesJson.put(path, fileToJson(entry));
                }
            }
            root.put("files", filesJson);

            String text = root.toString();
            if (text.equals(loadedText)) {
                // Nothing installed, updated or removed since the last scan:
                // spare the flash a rewrite of identical bytes.
                return true;
            }
            if (writeAtomically(file, text)) {
                loadedText = text;
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private void parse(JSONObject root) {
        if (root.optInt("format", 0) != FORMAT_VERSION) {
            return;
        }
        boolean sameBuild = selfStamp != null && selfStamp.equals(DeviceScanReport.optString(root, "self"));
        JSONObject appsJson = root.optJSONObject("apps");
        if (appsJson != null && sameBuild) {
            Iterator<String> keys = appsJson.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                AppEntry entry = appFromJson(pkg, appsJson.optJSONObject(pkg));
                if (entry != null) {
                    apps.put(pkg, entry);
                }
            }
        }
        JSONObject filesJson = root.optJSONObject("files");
        if (filesJson != null) {
            Iterator<String> keys = filesJson.keys();
            while (keys.hasNext()) {
                String path = keys.next();
                FileEntry entry = fileFromJson(path, filesJson.optJSONObject(path));
                if (entry != null) {
                    files.put(path, entry);
                }
            }
        }
    }

    private static JSONObject appToJson(AppEntry e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("key", e.key);
        o.put("level", e.level);
        if (e.labelLoaded) {
            o.put("labelLoaded", true);
            if (e.label != null) {
                o.put("label", e.label);
            }
            if (e.labelLocale != null) {
                o.put("labelLocale", e.labelLocale);
            }
        }
        if (!e.requestedPermissions.isEmpty()) {
            JSONArray perms = new JSONArray();
            for (String p : e.requestedPermissions) {
                // "android.permission." is most of the bytes of this file;
                // stored as a leading '@' and restored on read.
                perms.put(p.startsWith(PERMISSION_PREFIX) ? "@" + p.substring(PERMISSION_PREFIX.length()) : p);
            }
            o.put("perms", perms);
        }
        int flags = (e.declaresAccessibilityService ? 1 : 0)
                | (e.declaresNotificationListener ? 2 : 0)
                | (e.declaresDeviceAdmin ? 4 : 0)
                | (e.declaresSmsReceiver ? 8 : 0);
        o.put("decl", flags);
        if (!e.certSha256.isEmpty()) {
            JSONArray certs = new JSONArray();
            for (String c : e.certSha256) {
                certs.put(c);
            }
            o.put("certs", certs);
        }
        if (e.installer != null) {
            o.put("installer", e.installer);
        }
        o.put("source", e.packageSource);
        if (e.sha256 != null) {
            o.put("sha256", e.sha256);
            o.put("sha1", e.sha1);
            o.put("md5", e.md5);
        }
        return o;
    }

    private static AppEntry appFromJson(String pkg, JSONObject o) {
        if (o == null) {
            return null;
        }
        String key = DeviceScanReport.optString(o, "key");
        int level = o.optInt("level", 0);
        if (key == null || (level != AppEntry.LIGHT && level != AppEntry.FULL)) {
            return null;
        }
        AppEntry e = new AppEntry(pkg, key);
        e.level = level;
        e.labelLoaded = o.optBoolean("labelLoaded", false);
        e.label = DeviceScanReport.optString(o, "label");
        e.labelLocale = DeviceScanReport.optString(o, "labelLocale");
        JSONArray perms = o.optJSONArray("perms");
        if (perms != null) {
            Set<String> set = new LinkedHashSet<>();
            for (int i = 0; i < perms.length(); i++) {
                String p = perms.optString(i, null);
                if (p != null && !p.isEmpty()) {
                    set.add(p.charAt(0) == '@' ? PERMISSION_PREFIX + p.substring(1) : p);
                }
            }
            e.requestedPermissions = Collections.unmodifiableSet(set);
        }
        int flags = o.optInt("decl", 0);
        e.declaresAccessibilityService = (flags & 1) != 0;
        e.declaresNotificationListener = (flags & 2) != 0;
        e.declaresDeviceAdmin = (flags & 4) != 0;
        e.declaresSmsReceiver = (flags & 8) != 0;
        JSONArray certs = o.optJSONArray("certs");
        if (certs != null) {
            Set<String> set = new LinkedHashSet<>();
            for (int i = 0; i < certs.length(); i++) {
                String c = certs.optString(i, null);
                if (c != null && !c.isEmpty()) {
                    set.add(c);
                }
            }
            e.certSha256 = Collections.unmodifiableSet(set);
        }
        e.installer = DeviceScanReport.optString(o, "installer");
        e.packageSource = o.optInt("source", -1);
        String sha256 = DeviceScanReport.optString(o, "sha256");
        String sha1 = DeviceScanReport.optString(o, "sha1");
        String md5 = DeviceScanReport.optString(o, "md5");
        if (sha256 != null && sha1 != null && md5 != null) {
            e.sha256 = sha256;
            e.sha1 = sha1;
            e.md5 = md5;
        }
        return e;
    }

    private static JSONObject fileToJson(FileEntry e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("key", e.key);
        if (e.sha256 != null) {
            o.put("sha256", e.sha256);
            o.put("sha1", e.sha1);
            o.put("md5", e.md5);
        }
        if (e.verdictStamp != null && e.storedAssessment != null) {
            o.put("vstamp", e.verdictStamp);
            o.put("verdict", assessmentToJson(e.storedAssessment));
        }
        return o;
    }

    private static FileEntry fileFromJson(String path, JSONObject o) {
        if (o == null) {
            return null;
        }
        String key = DeviceScanReport.optString(o, "key");
        if (key == null) {
            return null;
        }
        FileEntry e = new FileEntry(path, key);
        String sha256 = DeviceScanReport.optString(o, "sha256");
        String sha1 = DeviceScanReport.optString(o, "sha1");
        String md5 = DeviceScanReport.optString(o, "md5");
        if (sha256 != null && sha1 != null && md5 != null) {
            e.sha256 = sha256;
            e.sha1 = sha1;
            e.md5 = md5;
        }
        String stamp = DeviceScanReport.optString(o, "vstamp");
        AppAssessment assessment = stamp != null ? assessmentFromJson(o.optJSONObject("verdict")) : null;
        if (assessment != null) {
            e.rememberAssessment(stamp, assessment);
        }
        return e;
    }

    /**
     * One {@code AppAssessment} for an APK file, faithfully — severity and
     * detail included, not just the codes, so a file whose verdict comes from
     * the cache reads exactly as it did when it was first judged.
     */
    private static JSONObject assessmentToJson(AppAssessment a) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("risk", a.getRisk().getLevel());
        o.put("v", a.getVerdict().getWire());
        o.put("src", a.getSource());
        if (a.getFamily() != null) {
            o.put("family", a.getFamily());
        }
        if (a.getPackageName() != null) {
            o.put("pkg", a.getPackageName());
        }
        if (a.getLabel() != null) {
            o.put("label", a.getLabel());
        }
        JSONArray signals = new JSONArray();
        for (Signal signal : a.getSignals()) {
            JSONObject s = new JSONObject();
            s.put("code", signal.getCode());
            s.put("sev", signal.getSeverity().name());
            if (signal.getDetail() != null) {
                s.put("detail", signal.getDetail());
            }
            List<String> args = signal.getArgs();
            if (!args.isEmpty()) {
                JSONArray argArray = new JSONArray();
                for (String arg : args) {
                    argArray.put(arg != null ? arg : "");
                }
                s.put("args", argArray);
            }
            signals.put(s);
        }
        o.put("signals", signals);
        return o;
    }

    private static AppAssessment assessmentFromJson(JSONObject o) {
        if (o == null || !o.has("risk") || !o.has("v")) {
            return null;
        }
        try {
            List<Signal> signals = new ArrayList<>();
            JSONArray array = o.optJSONArray("signals");
            for (int i = 0; array != null && i < array.length(); i++) {
                JSONObject s = array.optJSONObject(i);
                String code = s != null ? DeviceScanReport.optString(s, "code") : null;
                if (code == null || code.isEmpty()) {
                    continue;
                }
                Severity severity;
                try {
                    severity = Severity.valueOf(DeviceScanReport.optString(s, "sev"));
                } catch (Throwable t) {
                    // A severity this build does not know: the entry is no
                    // longer trustworthy as a whole, so re-evaluate the file.
                    return null;
                }
                JSONArray argArray = s.optJSONArray("args");
                List<String> args = new ArrayList<>(argArray != null ? argArray.length() : 0);
                for (int j = 0; argArray != null && j < argArray.length(); j++) {
                    args.add(argArray.optString(j, ""));
                }
                signals.add(new Signal(code, severity, DeviceScanReport.optString(s, "detail"),
                        Collections.unmodifiableList(args)));
            }
            return new AppAssessment(
                    AppRisk.fromLevel(o.optInt("risk", 0)),
                    verdictFromWire(DeviceScanReport.optString(o, "v")),
                    Collections.unmodifiableList(signals),
                    orDefault(DeviceScanReport.optString(o, "src"), "none"),
                    DeviceScanReport.optString(o, "family"),
                    DeviceScanReport.optString(o, "pkg"),
                    DeviceScanReport.optString(o, "label"));
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // small file helpers, shared with the report
    // ------------------------------------------------------------------

    /**
     * Replace {@code target} with {@code content} so that a reader sees either
     * the old file or the new one, never a torn one: write a sibling temporary
     * file, sync it to disk, then rename it over the target (atomic on the
     * same filesystem).
     */
    static boolean writeAtomically(File target, String content) {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            return false;
        }
        File tmp = new File(parent, target.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            try {
                out.getFD().sync();
            } catch (IOException ignored) {
                // Some filesystems refuse fsync; the rename is still atomic.
            }
            out.close();
            out = null;
            if (tmp.renameTo(target)) {
                return true;
            }
            // rename(2) replaces the target atomically on Android's ext4/f2fs,
            // so this is only reached on a filesystem that refuses to replace
            // (FAT-like). Deleting first loses atomicity there, but a scanner
            // whose cache silently stops being saved would be worse.
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (tmp.renameTo(target)) {
                return true;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** The file as UTF-8 text, or null if it does not exist or cannot be read. */
    static String readText(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), 4L * 1024 * 1024));
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static List<String> sorted(Set<String> keys) {
        List<String> list = new ArrayList<>(keys);
        Collections.sort(list);
        return list;
    }

    /** {@code Verdict} by its wire name; anything unrecognised is UNKNOWN, never CLEAN. */
    static Verdict verdictFromWire(String wire) {
        if (wire != null) {
            for (Verdict v : Verdict.values()) {
                if (v.getWire().equalsIgnoreCase(wire)) {
                    return v;
                }
            }
        }
        return Verdict.UNKNOWN;
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
