package uz.jac.secure.android;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;
import android.view.accessibility.AccessibilityManager;

import org.telegram.messenger.FileLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import uz.jac.secure.core.device.InstalledAppFacts;

/**
 * Reads what the device scanner needs to know about this phone, through the
 * public Android APIs an ordinary app is allowed to call.
 *
 * <h3>What we can see, and why that is enough</h3>
 *
 * Humogram does not hold {@code QUERY_ALL_PACKAGES}: Play reserves it for
 * antivirus apps, file managers and browsers, and a messenger with a scanner
 * feature is none of those. What it sees instead is what the manifest's
 * targeted {@code <queries>} make visible — every app with a launcher icon,
 * every app that declares an accessibility service or a notification
 * listener, every app that can hold the SMS role, and the stores and
 * installers themselves. That is precisely the population a banker belongs
 * to: it needs a launcher icon to be opened once, and accessibility or SMS
 * access to do anything. The blind spot — a payload with no icon and none of
 * those components — is accepted and stated rather than papered over.
 *
 * <h3>Two passes, so a launch scan is cheap</h3>
 *
 * <ol>
 *   <li>A cheap pass over everything visible
 *       ({@code getInstalledPackages(0)}), which yields each app's cache key
 *       ({@link DeviceScanCache}).</li>
 *   <li>A deep {@code getPackageInfo} — permissions, services, receivers,
 *       signing certificates — only for a package whose key missed. One call
 *       per package, never one call for all: a single reply holding every
 *       app's component list is how {@code TransactionTooLargeException}
 *       happens on a phone with a few hundred apps.</li>
 * </ol>
 *
 * Runtime state is the opposite: it is what a banker changes after it is
 * installed (its accessibility service switched on, SMS granted, its icon
 * disabled), so it is read fresh at every scan — and mostly in bulk, one call
 * per kind of state rather than one per app ({@link RuntimeState}).
 *
 * <h3>One bad package never stops the scan</h3>
 *
 * Every platform call here is API-guarded and wrapped: a package that
 * vanishes mid-scan, an OEM build that throws {@code SecurityException} from
 * an API documented not to, a manifest so large its reply cannot be
 * delivered. The worst any one of them can do is leave a field at its
 * "nothing known" default, which the core policy is written never to turn
 * into an alarm. Only {@code NameNotFoundException} — the package is gone —
 * propagates, so the caller drops that package instead of reporting on a
 * ghost.
 *
 * <p>Belongs to the scanner thread; not thread-safe. Never call it from the
 * main thread: every method here is binder or disk I/O.
 */
final class DeviceAppCollector {

    /** Polled between packages and between hash chunks. */
    interface CancelSignal {
        boolean isCancelled();
    }

    /** Thrown out of a long operation when {@link CancelSignal} says stop. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("device scan cancelled");
        }
    }

    /**
     * Largest APK we fingerprint. The malware this exists for ships in
     * single-digit megabytes; a two-gigabyte sideloaded game costs ten
     * seconds of eMMC reads per update and could not plausibly match a
     * published indicator. Larger files are still checked by every other
     * rule — they are just not hashed.
     */
    static final long MAX_FINGERPRINT_BYTES = 512L * 1024 * 1024;
    private static final int HASH_BUFFER_BYTES = 256 * 1024;

    /** Directory entries the APK-file walk may look at in one scan. */
    static final int MAX_WALK_ENTRIES = 2000;
    /** How far below each download folder the walk descends. */
    static final int MAX_WALK_DEPTH = 2;
    private static final String[] APK_EXTENSIONS = {".apk", ".apks", ".xapk", ".apkm"};

    /**
     * Permissions whose grant state is re-checked at every scan (and only if
     * the app requests them): the SMS group and CALL_PHONE, which is what
     * the core's SMS-access and USSD rules read. Each is one cheap
     * {@code checkPermission} call, so only the ones that matter are asked
     * about; a full per-permission sweep of every app would cost more than
     * the rest of the scan put together.
     */
    private static final String[] RUNTIME_CHECKED_PERMISSIONS = {
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_MMS",
            "android.permission.RECEIVE_WAP_PUSH",
            "android.permission.CALL_PHONE",
    };

    private static final String PERMISSION_SYSTEM_ALERT_WINDOW = "android.permission.SYSTEM_ALERT_WINDOW";
    private static final String PERMISSION_RECEIVE_SMS = "android.permission.RECEIVE_SMS";
    private static final String BIND_ACCESSIBILITY_SERVICE = "android.permission.BIND_ACCESSIBILITY_SERVICE";
    private static final String BIND_NOTIFICATION_LISTENER_SERVICE = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE";
    private static final String BIND_DEVICE_ADMIN = "android.permission.BIND_DEVICE_ADMIN";
    private static final String BROADCAST_SMS = "android.permission.BROADCAST_SMS";
    private static final String BROADCAST_WAP_PUSH = "android.permission.BROADCAST_WAP_PUSH";
    /**
     * {@code Settings.Secure.ENABLED_NOTIFICATION_LISTENERS} is {@code @hide},
     * but the value is readable and AndroidX's
     * {@code NotificationManagerCompat.getEnabledListenerPackages} reads the
     * same key. There is no public API that lists other apps' listeners.
     */
    private static final String ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners";

    private final Context context;
    private final PackageManager pm;
    private final DeviceScanCache cache;
    private final CancelSignal cancel;
    private final String selfPackage;
    private final String locale;
    /** Installer package → what it turned out to be, for this scan only. See {@link #installerIdentity}. */
    private final HashMap<String, InstallerIdentity> installers = new HashMap<>();
    private byte[] hashBuffer;

    DeviceAppCollector(Context context, DeviceScanCache cache, CancelSignal cancel) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.pm = this.context.getPackageManager();
        this.cache = cache;
        this.cancel = cancel;
        this.selfPackage = this.context.getPackageName();
        this.locale = Locale.getDefault().toString();
    }

    // ------------------------------------------------------------------
    // runtime state, read once per scan
    // ------------------------------------------------------------------

    /**
     * Phone-wide state that decides whether an app is RUNNING as a threat
     * rather than merely installed. Read in bulk, once per scan.
     */
    static final class RuntimeState {
        /** Packages with an enabled accessibility service. */
        final Set<String> accessibility = new HashSet<>();
        /** Packages with an enabled notification listener. */
        final Set<String> notificationListeners = new HashSet<>();
        /** Packages that are an active device administrator. */
        final Set<String> deviceAdmins = new HashSet<>();
        /** The default SMS app, or null (none, or not visible to us). */
        String defaultSms;
        /** Packages declaring a MAIN/LAUNCHER activity, disabled ones included; null if the query failed. */
        Set<String> launcherDeclared;
        /** Packages with an enabled MAIN/LAUNCHER activity; null if the query failed. */
        Set<String> launcherEnabled;
    }

    RuntimeState readRuntimeState() {
        RuntimeState state = new RuntimeState();
        ContentResolver resolver = context.getContentResolver();

        // Accessibility: the Settings string (every enabled service, bound or
        // not, and not filtered by package visibility) plus the manager's
        // list (the services actually bound right now). The union, because
        // either alone can miss one: the string lags a crashed service, and
        // the bound list lags one that is enabled but not yet started.
        try {
            addComponentPackages(Settings.Secure.getString(resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), state.accessibility);
        } catch (Throwable ignored) {
        }
        try {
            AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            List<AccessibilityServiceInfo> enabled = manager != null
                    ? manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) : null;
            if (enabled != null) {
                for (AccessibilityServiceInfo info : enabled) {
                    ResolveInfo resolve = info != null ? info.getResolveInfo() : null;
                    if (resolve != null && resolve.serviceInfo != null && resolve.serviceInfo.packageName != null) {
                        state.accessibility.add(resolve.serviceInfo.packageName);
                    } else if (info != null) {
                        addComponentPackages(info.getId(), state.accessibility);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            addComponentPackages(Settings.Secure.getString(resolver, ENABLED_NOTIFICATION_LISTENERS),
                    state.notificationListeners);
        } catch (Throwable ignored) {
        }

        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            // getActiveAdmins() returns null, not an empty list, when there
            // are none.
            List<ComponentName> admins = dpm != null ? dpm.getActiveAdmins() : null;
            if (admins != null) {
                for (ComponentName admin : admins) {
                    if (admin != null) {
                        state.deviceAdmins.add(admin.getPackageName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            // Visibility-filtered on Android 11+: it needs the SENDTO smsto
            // query in the manifest, or it answers null for everyone.
            state.defaultSms = Telephony.Sms.getDefaultSmsPackage(context);
        } catch (Throwable ignored) {
        }

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        state.launcherDeclared = packagesOf(queryActivities(launcher, disabledComponentsFlag()));
        state.launcherEnabled = packagesOf(queryActivities(launcher, 0));
        return state;
    }

    // ------------------------------------------------------------------
    // installed packages
    // ------------------------------------------------------------------

    /**
     * One visible installed package: the cheap facts from the first pass,
     * then the static entry and the runtime state as the scanner fills them
     * in.
     */
    static final class Candidate {
        final String packageName;
        final String versionName;
        final long versionCode;
        final boolean system;
        final boolean updatedSystem;
        final boolean debuggable;
        final boolean appEnabled;
        final long firstInstallTime;
        final long lastUpdateTime;
        final int targetSdk;
        final int uid;
        final String sourceDir;
        final long apkSize;
        final String cacheKey;

        DeviceScanCache.AppEntry entry;
        boolean cacheMiss;

        Set<String> granted = Collections.emptySet();
        boolean overlayAllowed;
        boolean accessibilityEnabled;
        boolean notificationListenerEnabled;
        boolean deviceAdminActive;
        boolean defaultSmsApp;
        boolean hasLauncherActivity;
        boolean launcherEnabled;
        /** Whether the installer is preinstalled; null when that could not be read. */
        Boolean installerSystem;
        Set<String> installerCerts = Collections.emptySet();

        /** Whether base.apk was fingerprinted in THIS scan (as opposed to reused). */
        boolean hashedNow;

        Candidate(PackageInfo info, ApplicationInfo app, long versionCode, long apkSize) {
            this.packageName = info.packageName;
            this.versionName = info.versionName;
            this.versionCode = versionCode;
            this.system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            this.updatedSystem = (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            this.debuggable = (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            this.appEnabled = app.enabled;
            this.firstInstallTime = info.firstInstallTime;
            this.lastUpdateTime = info.lastUpdateTime;
            this.targetSdk = app.targetSdkVersion;
            this.uid = app.uid;
            this.sourceDir = app.sourceDir;
            this.apkSize = apkSize;
            this.cacheKey = packageName + "|" + versionCode + "|" + lastUpdateTime + "|" + sourceDir + "|" + apkSize;
        }

        /** System app for the scan's purposes: only threat intel is evaluated. Same rule as the core. */
        boolean isSystemForPolicy() {
            return system || updatedSystem;
        }

        boolean hasFingerprint() {
            return entry != null && entry.hasFingerprint();
        }

        /** What to show while this package is being checked. */
        String displayName() {
            String label = entry != null ? entry.label : null;
            return label != null && label.trim().length() > 0 ? label : packageName;
        }

        /**
         * The facts the core policy judges, from the static entry and this
         * scan's runtime state. The one place a {@code Builder} is filled in.
         */
        InstalledAppFacts toFacts() {
            DeviceScanCache.AppEntry e = entry;
            InstalledAppFacts.Builder b = new InstalledAppFacts.Builder(packageName)
                    .versionCode(versionCode)
                    .versionName(versionName)
                    .isSystem(system)
                    .isUpdatedSystem(updatedSystem)
                    .firstInstallTime(firstInstallTime)
                    .lastUpdateTime(lastUpdateTime)
                    .targetSdk(targetSdk)
                    .debuggable(debuggable)
                    .apkSize(apkSize)
                    .grantedPermissions(granted)
                    .overlayAllowed(overlayAllowed)
                    .accessibilityEnabled(accessibilityEnabled)
                    .notificationListenerEnabled(notificationListenerEnabled)
                    .deviceAdminActive(deviceAdminActive)
                    .isDefaultSmsApp(defaultSmsApp)
                    .hasLauncherActivity(hasLauncherActivity)
                    .launcherEnabled(launcherEnabled);
            if (e != null) {
                // An unresolved label goes to the core as the package name:
                // that is PackageManager's own fallback for an app with no
                // label, which the core reads as "no presented name" — not
                // null, which it would read as a blank (and suspicious) one.
                b.label(e.labelLoaded && e.label != null ? e.label : packageName)
                        .installer(e.installer)
                        .packageSource(e.packageSource)
                        .requestedPermissions(e.requestedPermissions)
                        .declaresAccessibilityService(e.declaresAccessibilityService)
                        .declaresNotificationListener(e.declaresNotificationListener)
                        .declaresDeviceAdmin(e.declaresDeviceAdmin)
                        .declaresSmsReceiver(e.declaresSmsReceiver)
                        .certSha256(e.certSha256)
                        .apkSha256(e.sha256)
                        .apkSha1(e.sha1)
                        .apkMd5(e.md5)
                        .installerCertSha256(installerCerts);
                // Left unset when unknown: to the core that means "nobody
                // looked", and the installer's name then stands.
                if (installerSystem != null) {
                    b.installerIsSystem(installerSystem.booleanValue());
                }
            } else {
                b.label(packageName);
            }
            return b.build();
        }
    }

    /**
     * The cheap pass: every package visible to us, minus Humogram itself and
     * archived apps. One call; PackageManager pages the reply, so its size is
     * not a concern here the way component lists are.
     */
    @SuppressWarnings("deprecation")
    List<Candidate> listPackages() {
        List<PackageInfo> installed;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            installed = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0));
        } else {
            installed = pm.getInstalledPackages(0);
        }
        List<Candidate> out = new ArrayList<>(installed != null ? installed.size() : 0);
        if (installed == null) {
            return out;
        }
        for (PackageInfo info : installed) {
            try {
                if (info == null || info.packageName == null || info.applicationInfo == null) {
                    continue;
                }
                if (info.packageName.equals(selfPackage)) {
                    continue;
                }
                ApplicationInfo app = info.applicationInfo;
                // An archived app (Android 15) has no APK on the phone, only
                // its icon and data; there is nothing to scan until it is
                // restored, at which point it is a new install.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && app.isArchived) {
                    continue;
                }
                long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? info.getLongVersionCode() : info.versionCode;
                long apkSize = 0;
                if (app.sourceDir != null) {
                    try {
                        apkSize = new File(app.sourceDir).length();
                    } catch (Throwable ignored) {
                    }
                }
                out.add(new Candidate(info, app, versionCode, apkSize));
            } catch (Throwable ignored) {
                // One odd PackageInfo is skipped, not the whole list.
            }
        }
        return out;
    }

    /**
     * Fill {@link Candidate#entry}: from the cache when the key still
     * matches, otherwise from a fresh deep read (then {@link Candidate#cacheMiss}).
     *
     * @throws PackageManager.NameNotFoundException the package was
     *         uninstalled since the cheap pass; drop it
     */
    void resolveStatic(Candidate c) throws PackageManager.NameNotFoundException {
        int wanted = c.isSystemForPolicy() ? DeviceScanCache.AppEntry.LIGHT : DeviceScanCache.AppEntry.FULL;
        DeviceScanCache.AppEntry cached = cache.getApp(c.packageName, c.cacheKey);
        if (cached != null && cached.level >= wanted) {
            c.entry = cached;
            c.cacheMiss = false;
            // A label is resolved in the phone's language; if that changed,
            // reload just the label, not the whole entry.
            if (wanted == DeviceScanCache.AppEntry.FULL && (!cached.labelLoaded || !locale.equals(cached.labelLocale))) {
                loadLabel(cached);
            }
            return;
        }

        DeviceScanCache.AppEntry entry = new DeviceScanCache.AppEntry(c.packageName, c.cacheKey);
        entry.level = wanted;
        readInstaller(entry);
        if (wanted == DeviceScanCache.AppEntry.FULL) {
            readDeep(entry);
            loadLabel(entry);
        } else {
            // A system app is judged on threat intel alone (package, signer,
            // hash), so the certificates are all it needs. Its label is
            // loaded later, and only if something matched.
            readSigningOnly(entry);
        }
        c.entry = entry;
        c.cacheMiss = true;
        cache.putApp(entry);
    }

    /** Load the label of a (system) app that turned out to need one for the report. */
    void ensureLabel(Candidate c) {
        DeviceScanCache.AppEntry entry = c.entry;
        if (entry != null && (!entry.labelLoaded || !locale.equals(entry.labelLocale))) {
            loadLabel(entry);
        }
    }

    /** Fill in the runtime half of the facts. Cheap: set lookups, plus a few calls for apps that ask for SMS or overlay. */
    void resolveRuntime(Candidate c, RuntimeState state) {
        String pkg = c.packageName;
        c.accessibilityEnabled = state.accessibility.contains(pkg);
        c.notificationListenerEnabled = state.notificationListeners.contains(pkg);
        c.deviceAdminActive = state.deviceAdmins.contains(pkg);
        c.defaultSmsApp = pkg.equals(state.defaultSms);
        c.hasLauncherActivity = state.launcherDeclared != null
                ? state.launcherDeclared.contains(pkg) : declaresLauncher(pkg);
        String installer = c.entry != null ? c.entry.installer : null;
        if (installer != null) {
            InstallerIdentity identity = installerIdentity(installer);
            c.installerSystem = identity.system;
            c.installerCerts = identity.certSha256;
        }

        if (c.isSystemForPolicy()) {
            // Only threat intel is evaluated for system apps; the per-app
            // calls below would be spent on nothing.
            c.launcherEnabled = c.hasLauncherActivity;
            return;
        }

        DeviceScanCache.AppEntry entry = c.entry;
        Set<String> requested = entry != null ? entry.requestedPermissions : Collections.<String>emptySet();
        c.granted = grantedOf(pkg, requested);
        c.overlayAllowed = overlayAllowed(pkg, c.uid, requested);

        if (!c.hasLauncherActivity) {
            c.launcherEnabled = false;
        } else if (!c.appEnabled) {
            // The whole app is disabled (adb, or an MDM): it cannot run at
            // all, so "hid its icon" would be a false and alarming sentence.
            c.launcherEnabled = true;
        } else if (state.launcherEnabled != null && state.launcherEnabled.contains(pkg)) {
            c.launcherEnabled = true;
        } else {
            // The bulk query says the icon is gone; confirm with the exact
            // API the launcher uses before accusing the app of hiding.
            boolean launchable;
            try {
                launchable = pm.getLaunchIntentForPackage(pkg) != null;
            } catch (Throwable t) {
                // Unknown is not evidence.
                launchable = true;
            }
            if (launchable) {
                c.launcherEnabled = true;
            } else if (launcherDisabledAtRuntime(pkg)) {
                // setComponentEnabledSetting(..., DISABLED) after install:
                // the Ajina move, icon gone once the permissions were granted.
                c.launcherEnabled = false;
            } else {
                // Disabled in its own manifest and never switched on: this
                // app never showed an icon, so it has not hidden one. Reported
                // as having no launcher rather than a hidden one — "hid its
                // icon" is an accusation, and here it would be false.
                c.hasLauncherActivity = false;
                c.launcherEnabled = false;
            }
        }
    }

    /**
     * Whether one of the package's launcher activities was switched off at
     * runtime (as opposed to shipping disabled in its manifest).
     * {@code getComponentEnabledSetting} answers DEFAULT for a component
     * nobody has touched and DISABLED for one an app turned off. Failing to
     * ask is treated as "no".
     */
    private boolean launcherDisabledAtRuntime(String pkg) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        List<ResolveInfo> launchers = queryActivities(intent, disabledComponentsFlag());
        if (launchers == null) {
            return false;
        }
        for (ResolveInfo info : launchers) {
            ActivityInfo activity = info != null ? info.activityInfo : null;
            if (activity == null || activity.name == null) {
                continue;
            }
            try {
                int state = pm.getComponentEnabledSetting(new ComponentName(pkg, activity.name));
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // deep read
    // ------------------------------------------------------------------

    private void readDeep(DeviceScanCache.AppEntry entry) throws PackageManager.NameNotFoundException {
        String pkg = entry.packageName;
        int all = PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                | disabledComponentsFlag() | signingFlag();
        PackageInfo info = null;
        try {
            info = packageInfo(pkg, all);
        } catch (PackageManager.NameNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            // Usually a reply too large for the binder (an app with thousands
            // of components). Fall through to one part at a time, so the part
            // that is too big costs us only itself.
        }
        if (info != null) {
            applyPermissions(entry, info);
            applyServices(entry, info);
            applyReceivers(entry, info);
            applySigning(entry, info);
        } else {
            boolean ok = true;
            ok &= readPart(entry, PackageManager.GET_PERMISSIONS, PART_PERMISSIONS);
            ok &= readPart(entry, PackageManager.GET_SERVICES | disabledComponentsFlag(), PART_SERVICES);
            ok &= readPart(entry, PackageManager.GET_RECEIVERS | disabledComponentsFlag(), PART_RECEIVERS);
            ok &= readPart(entry, signingFlag(), PART_SIGNING);
            // Something is missing; use what we have this once, but do not
            // cache it, so the next scan tries again. &=, not =: readInstaller
            // may already have marked the entry incomplete.
            entry.complete &= ok;
        }
        if (entry.requestedPermissions.contains(PERMISSION_RECEIVE_SMS)) {
            // The design's approximation of "has an SMS receiver": receiver
            // intent filters are not in PackageInfo, and SMS_RECEIVED is a
            // protected broadcast that <queries> cannot target either.
            entry.declaresSmsReceiver = true;
        }
    }

    private void readSigningOnly(DeviceScanCache.AppEntry entry) throws PackageManager.NameNotFoundException {
        entry.complete &= readPart(entry, signingFlag(), PART_SIGNING);
    }

    private static final int PART_PERMISSIONS = 0;
    private static final int PART_SERVICES = 1;
    private static final int PART_RECEIVERS = 2;
    private static final int PART_SIGNING = 3;

    private boolean readPart(DeviceScanCache.AppEntry entry, int flags, int part)
            throws PackageManager.NameNotFoundException {
        try {
            PackageInfo info = packageInfo(entry.packageName, flags);
            switch (part) {
                case PART_PERMISSIONS:
                    applyPermissions(entry, info);
                    break;
                case PART_SERVICES:
                    applyServices(entry, info);
                    break;
                case PART_RECEIVERS:
                    applyReceivers(entry, info);
                    break;
                case PART_SIGNING:
                    applySigning(entry, info);
                    break;
                default:
                    break;
            }
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyPermissions(DeviceScanCache.AppEntry entry, PackageInfo info) {
        String[] requested = info.requestedPermissions;
        if (requested == null || requested.length == 0) {
            entry.requestedPermissions = Collections.emptySet();
            return;
        }
        Set<String> set = new LinkedHashSet<>();
        for (String p : requested) {
            if (p != null) {
                set.add(p);
            }
        }
        entry.requestedPermissions = Collections.unmodifiableSet(set);
    }

    private static void applyServices(DeviceScanCache.AppEntry entry, PackageInfo info) {
        ServiceInfo[] services = info.services;
        if (services == null) {
            return;
        }
        for (ServiceInfo service : services) {
            if (service == null) {
                continue;
            }
            if (BIND_ACCESSIBILITY_SERVICE.equals(service.permission)) {
                entry.declaresAccessibilityService = true;
            } else if (BIND_NOTIFICATION_LISTENER_SERVICE.equals(service.permission)) {
                entry.declaresNotificationListener = true;
            }
        }
    }

    private static void applyReceivers(DeviceScanCache.AppEntry entry, PackageInfo info) {
        ActivityInfo[] receivers = info.receivers;
        if (receivers == null) {
            return;
        }
        for (ActivityInfo receiver : receivers) {
            if (receiver == null) {
                continue;
            }
            if (BIND_DEVICE_ADMIN.equals(receiver.permission)) {
                entry.declaresDeviceAdmin = true;
            } else if (BROADCAST_SMS.equals(receiver.permission) || BROADCAST_WAP_PUSH.equals(receiver.permission)) {
                // What the platform requires of an SMS_DELIVER / WAP_PUSH_DELIVER
                // receiver, i.e. of an app that wants to be the SMS app.
                entry.declaresSmsReceiver = true;
            }
        }
    }

    /**
     * Signing-certificate digests, as lowercase hex SHA-256 of each
     * certificate's DER encoding — the same value {@code ApkSignature}
     * produces for an APK file and the brand allowlist pins.
     *
     * <p>With key rotation (API 28+) the whole lineage is included, not just
     * the current signer: a brand pinned on its original key must still
     * match after it rotates, and a malware family's known certificate must
     * still match an app that rotated away from it.
     */
    private static void applySigning(DeviceScanCache.AppEntry entry, PackageInfo info) {
        Set<String> digests = signingDigests(info);
        if (!digests.isEmpty()) {
            entry.certSha256 = digests;
        }
    }

    /** The digests {@link #applySigning} stores; empty, never null, when there are none. */
    @SuppressWarnings("deprecation")
    private static Set<String> signingDigests(PackageInfo info) {
        Signature[] signatures = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signing = info.signingInfo;
            if (signing != null) {
                signatures = signing.hasMultipleSigners()
                        ? signing.getApkContentsSigners()
                        : signing.getSigningCertificateHistory();
            }
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) {
            return Collections.emptySet();
        }
        Set<String> digests = new LinkedHashSet<>();
        for (Signature signature : signatures) {
            if (signature == null) {
                continue;
            }
            try {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                digests.add(hex(sha256.digest(signature.toByteArray())));
            } catch (Throwable ignored) {
            }
        }
        return Collections.unmodifiableSet(digests);
    }

    /**
     * Who the installer really is, as opposed to what it is called.
     *
     * <p>The core refuses to take a store's package name at its word: names
     * are not reserved, so a dropper can call itself {@code com.bbk.appstore}
     * on a phone that never had vivo's store, and its payload would then pass
     * as a store install with no runtime rule and no fingerprint. What a
     * dropper cannot fake is being preinstalled, or carrying the store's
     * signing certificate — so those two facts are read here and the core
     * decides.
     *
     * <p>Read every scan rather than cached with the app: they describe the
     * installer, which can be replaced or removed without the app's own cache
     * key changing. One lookup per distinct installer per scan.
     *
     * <p>Tri-state on purpose. {@code system == null} means the lookup itself
     * failed and nothing is known, which the core treats as "did not look"
     * and lets the name stand — the safe side, since the alternative is
     * calling every Play install on the phone sideloaded because the package
     * manager was busy. A package that is simply gone is a definite answer:
     * not preinstalled.
     */
    private static final class InstallerIdentity {
        Boolean system;
        Set<String> certSha256 = Collections.emptySet();
    }

    private InstallerIdentity installerIdentity(String installer) {
        InstallerIdentity known = installers.get(installer);
        if (known != null) {
            return known;
        }
        InstallerIdentity identity = new InstallerIdentity();
        try {
            PackageInfo info = packageInfo(installer, signingFlag());
            ApplicationInfo app = info.applicationInfo;
            if (app != null) {
                identity.system = (app.flags
                        & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                if (!identity.system) {
                    identity.certSha256 = signingDigests(info);
                }
            }
        } catch (PackageManager.NameNotFoundException gone) {
            identity.system = Boolean.FALSE;
        } catch (Throwable ignored) {
        }
        installers.put(installer, identity);
        return identity;
    }

    /**
     * The installing package and (API 33+) the install source.
     *
     * <p>On Android 11+ both come back null when the installer is not visible
     * to us — which is why the manifest declares the stores and installers as
     * {@code <package>} queries. {@code getInstallerPackageName} is deprecated
     * from 30 and used below it, and above it only as the fallback described
     * next.
     *
     * <p>A <em>failed</em> read is not the same as "no installer". If
     * {@code getInstallSourceInfo} throws — the package manager busy installing
     * updates during a launch scan, or an OEM build that throws
     * SecurityException from an API documented not to — then leaving the
     * installer null would cache a Play-installed app as sideloaded with
     * {@code how = unknown}, and it would stay that way at every launch until
     * its version, update time, path or size changed. So the deprecated call is
     * tried next, and if that fails too the entry is marked incomplete: used for
     * this scan, not written to the cache, re-read next time.
     */
    @SuppressWarnings("deprecation")
    private void readInstaller(DeviceScanCache.AppEntry entry) {
        String pkg = entry.packageName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                InstallSourceInfo source = pm.getInstallSourceInfo(pkg);
                entry.installer = source.getInstallingPackageName();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    entry.packageSource = source.getPackageSource();
                }
            } catch (Throwable ignored) {
                try {
                    entry.installer = pm.getInstallerPackageName(pkg);
                } catch (Throwable alsoIgnored) {
                    entry.complete = false;
                }
            }
        } else {
            try {
                entry.installer = pm.getInstallerPackageName(pkg);
            } catch (Throwable ignored) {
            }
        }
    }

    private void loadLabel(DeviceScanCache.AppEntry entry) {
        try {
            ApplicationInfo app;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app = pm.getApplicationInfo(entry.packageName, PackageManager.ApplicationInfoFlags.of(0));
            } else {
                app = pm.getApplicationInfo(entry.packageName, 0);
            }
            // loadLabel falls back to the package name for an app with no
            // label at all; the core knows that and does not treat it as a
            // presented name.
            CharSequence label = app.loadLabel(pm);
            entry.label = label != null ? label.toString() : null;
            entry.labelLocale = locale;
            entry.labelLoaded = true;
        } catch (Throwable t) {
            // Not loaded; retried next scan. Meanwhile the facts carry the
            // package name (see Candidate.toFacts).
            entry.labelLoaded = false;
        }
    }

    // ------------------------------------------------------------------
    // runtime, per app
    // ------------------------------------------------------------------

    private Set<String> grantedOf(String pkg, Set<String> requested) {
        if (requested.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> granted = null;
        for (String permission : RUNTIME_CHECKED_PERMISSIONS) {
            if (!requested.contains(permission)) {
                continue;
            }
            try {
                if (pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED) {
                    if (granted == null) {
                        granted = new HashSet<>();
                    }
                    granted.add(permission);
                }
            } catch (Throwable ignored) {
            }
        }
        return granted != null ? granted : Collections.<String>emptySet();
    }

    /**
     * Whether the app may draw over other apps right now.
     *
     * <p>SYSTEM_ALERT_WINDOW is an app-op permission from Android 6: the
     * switch in Settings is the op, and MODE_DEFAULT falls back to the
     * permission grant (true only for pre-23 targets) — the same rule
     * {@code Settings.canDrawOverlays} applies to the calling app. Below 6
     * the permission was granted at install to anyone who asked.
     */
    private boolean overlayAllowed(String pkg, int uid, Set<String> requested) {
        if (!requested.contains(PERMISSION_SYSTEM_ALERT_WINDOW)) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg);
            if (mode == AppOpsManager.MODE_ALLOWED) {
                return true;
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return pm.checkPermission(PERMISSION_SYSTEM_ALERT_WINDOW, pkg) == PackageManager.PERMISSION_GRANTED;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Per-package fallback for when the bulk launcher query failed. */
    private boolean declaresLauncher(String pkg) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        Set<String> packages = packagesOf(queryActivities(intent, disabledComponentsFlag()));
        return packages != null && packages.contains(pkg);
    }

    // ------------------------------------------------------------------
    // fingerprints
    // ------------------------------------------------------------------

    /** SHA-256, SHA-1 and MD5 of one file, lowercase hex, from one read. */
    static final class Fingerprint {
        final String sha256;
        final String sha1;
        final String md5;
        final long bytes;

        Fingerprint(String sha256, String sha1, String md5, long bytes) {
            this.sha256 = sha256;
            this.sha1 = sha1;
            this.md5 = md5;
            this.bytes = bytes;
        }
    }

    /**
     * Fingerprint an installed app's base.apk into its cache entry, unless it
     * is too large or already done. Returns true when the entry now has one.
     *
     * <p>base.apk is world-readable (0644, and SELinux allows apps to read
     * {@code apk_data_file}), so this needs no permission; the randomised
     * path is what package visibility protects, and we have it.
     */
    boolean fingerprintApp(Candidate c) {
        if (c.hasFingerprint()) {
            return true;
        }
        if (c.entry == null || c.sourceDir == null || c.apkSize <= 0 || c.apkSize > MAX_FINGERPRINT_BYTES) {
            return false;
        }
        Fingerprint fp = fingerprint(new File(c.sourceDir));
        if (fp == null || fp.bytes != c.apkSize) {
            // Unreadable, or the file changed under us (an update landing
            // mid-scan): no fingerprint rather than a wrong one.
            return false;
        }
        c.entry.sha256 = fp.sha256;
        c.entry.sha1 = fp.sha1;
        c.entry.md5 = fp.md5;
        c.hashedNow = true;
        return true;
    }

    /**
     * One pass over the file, three digests. Null if it cannot be read.
     *
     * @throws Cancelled if the scan is cancelled between two chunks
     */
    Fingerprint fingerprint(File file) {
        if (hashBuffer == null) {
            hashBuffer = new byte[HASH_BUFFER_BYTES];
        }
        InputStream in = null;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            in = new FileInputStream(file);
            long total = 0;
            int read;
            while (true) {
                if (cancel.isCancelled()) {
                    throw new Cancelled();
                }
                read = in.read(hashBuffer);
                if (read < 0) {
                    break;
                }
                if (read > 0) {
                    sha256.update(hashBuffer, 0, read);
                    sha1.update(hashBuffer, 0, read);
                    md5.update(hashBuffer, 0, read);
                    total += read;
                }
            }
            return new Fingerprint(hex(sha256.digest()), hex(sha1.digest()), hex(md5.digest()), total);
        } catch (Cancelled c) {
            throw c;
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

    // ------------------------------------------------------------------
    // APK files in our own folders
    // ------------------------------------------------------------------

    /** One loose APK file and its (cached or fresh) fingerprint. */
    static final class ApkFile {
        final File file;
        final String path;
        final long size;
        final long modified;
        final String key;
        /**
         * Which {@code FileLoader.MEDIA_DIR_*} root this file was found under.
         *
         * <p>{@link FileLoader#MEDIA_DIR_CACHE} is the one that matters:
         * upstream stores every <em>secret-chat</em> document there
         * ({@code FileLoader}: {@code if (document.key != null) type =
         * MEDIA_DIR_CACHE}), decrypted, under a generated name. Such a file is
         * checked on this phone like any other, but nothing derived from it may
         * be written to disk — see {@link #fingerprintFile}.
         */
        final int rootType;
        DeviceScanCache.FileEntry entry;

        ApkFile(File file, long size, long modified, int rootType) {
            this.file = file;
            this.path = file.getAbsolutePath();
            this.size = size;
            this.modified = modified;
            this.rootType = rootType;
            this.key = path + "|" + size + "|" + modified;
        }

        /** True for a file under the cache root, which may hold secret-chat documents. */
        boolean isCacheRoot() {
            return rootType == FileLoader.MEDIA_DIR_CACHE;
        }
    }

    /**
     * APK files in Humogram's own Documents, Files and cache folders.
     *
     * <p>Only our own folders, because on Android 11+ they are all a
     * messenger can read without {@code MANAGE_EXTERNAL_STORAGE} — and they
     * are where an APK forwarded over Telegram lands, which is how the
     * bankers this exists for arrive. Being ours, the page can also delete
     * what it finds.
     *
     * <p>Bounded: at most {@link #MAX_WALK_DEPTH} levels below each folder and
     * {@link #MAX_WALK_ENTRIES} directory entries <em>per folder</em>. Per
     * folder rather than shared, because one folder must not be able to starve
     * the next: a Telegram Documents with thousands of entries would otherwise
     * leave nothing for Telegram Files, which is exactly where a chat document
     * saved under its own name lands ({@code FileLoader}: {@code storeFileName
     * = getDocumentFileName(document); getDirectory(MEDIA_DIR_FILES)}). Within
     * each directory the names ending in an APK extension are taken first (a
     * string check, no disk access), and only then are other entries examined
     * to find subfolders, so the budget runs out on thumbnails rather than on
     * installers. Quarantined files are skipped: they are already handled.
     */
    List<ApkFile> findApkFiles() {
        List<ApkFile> out = new ArrayList<>();
        Set<String> seenRoots = new HashSet<>();
        Set<String> seenFiles = new HashSet<>();
        String quarantine = null;
        try {
            quarantine = new QuarantineStore(context).getRoot().getCanonicalPath();
        } catch (Throwable ignored) {
        }
        // Files first, then Documents: a document saved under its own name —
        // the "Payme.apk" a victim is sent — goes to Files, so that folder is
        // the one whose budget must never be spent elsewhere.
        int[] types = {FileLoader.MEDIA_DIR_FILES, FileLoader.MEDIA_DIR_DOCUMENT, FileLoader.MEDIA_DIR_CACHE};
        String cacheRoot = null;
        try {
            File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            cacheRoot = dir != null ? dir.getCanonicalPath() : null;
        } catch (Throwable ignored) {
        }
        for (int type : types) {
            File root;
            try {
                root = FileLoader.getDirectory(type);
            } catch (Throwable t) {
                // FileLoader's folders are set up by ImageLoader; before that
                // there is nothing to walk.
                root = null;
            }
            if (root == null) {
                continue;
            }
            String canonical;
            try {
                canonical = root.getCanonicalPath();
            } catch (IOException e) {
                continue;
            }
            // getDirectory() falls back to the cache folder for a type it
            // has no folder for; walk each real folder once.
            if (!seenRoots.add(canonical)) {
                continue;
            }
            // …and when it did fall back, what we are walking IS the cache,
            // whatever type we asked for. The secret-chat rule follows the
            // bytes on disk, not the constant we passed in.
            int rootType = cacheRoot != null && cacheRoot.equals(canonical)
                    ? FileLoader.MEDIA_DIR_CACHE : type;
            int[] budget = {MAX_WALK_ENTRIES};
            walk(root, 0, budget, quarantine, seenFiles, rootType, out);
        }
        return out;
    }

    private void walk(File dir, int depth, int[] budget, String quarantine, Set<String> seenFiles,
                      int rootType, List<ApkFile> out) {
        if (budget[0] <= 0 || cancel.isCancelled()) {
            return;
        }
        String canonicalDir;
        try {
            canonicalDir = dir.getCanonicalPath();
        } catch (IOException e) {
            return;
        }
        if (quarantine != null && (canonicalDir.equals(quarantine) || canonicalDir.startsWith(quarantine + File.separator))) {
            return;
        }
        String[] names;
        try {
            names = dir.list();
        } catch (Throwable t) {
            names = null;
        }
        if (names == null) {
            return;
        }
        List<String> others = depth < MAX_WALK_DEPTH ? new ArrayList<String>() : null;
        for (String name : names) {
            if (budget[0] <= 0) {
                return;
            }
            if (hasApkExtension(name)) {
                budget[0]--;
                File file = new File(dir, name);
                try {
                    if (file.isFile() && seenFiles.add(file.getCanonicalPath())) {
                        out.add(new ApkFile(file, file.length(), file.lastModified(), rootType));
                    }
                } catch (Throwable ignored) {
                }
            } else if (others != null && !name.startsWith(".")) {
                others.add(name);
            }
        }
        if (others == null) {
            return;
        }
        for (String name : others) {
            if (budget[0] <= 0 || cancel.isCancelled()) {
                return;
            }
            budget[0]--;
            File child = new File(dir, name);
            try {
                if (child.isDirectory()) {
                    walk(child, depth + 1, budget, quarantine, seenFiles, rootType, out);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Attach the cached fingerprint, or compute a new one. False if unreadable.
     *
     * <p>A file under the cache root is fingerprinted but never cached: that
     * folder holds secret-chat documents, and the path and hash of one must not
     * outlive the scan that read them. It costs one re-read per launch for the
     * rare APK that sits there.
     */
    boolean fingerprintFile(ApkFile apk) {
        if (!apk.isCacheRoot()) {
            DeviceScanCache.FileEntry cached = cache.getFile(apk.path, apk.key);
            if (cached != null && cached.sha256 != null) {
                apk.entry = cached;
                return true;
            }
        }
        if (apk.size <= 0 || apk.size > MAX_FINGERPRINT_BYTES) {
            return false;
        }
        Fingerprint fp = fingerprint(apk.file);
        if (fp == null || fp.bytes != apk.size) {
            return false;
        }
        DeviceScanCache.FileEntry entry = new DeviceScanCache.FileEntry(apk.path, apk.key);
        entry.sha256 = fp.sha256;
        entry.sha1 = fp.sha1;
        entry.md5 = fp.md5;
        if (!apk.isCacheRoot()) {
            cache.putFile(entry);
        }
        apk.entry = entry;
        return true;
    }

    static boolean hasApkExtension(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : APK_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // PackageManager plumbing
    // ------------------------------------------------------------------

    private PackageInfo packageInfo(String pkg, int flags) throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags));
        }
        return pm.getPackageInfo(pkg, flags);
    }

    private List<ResolveInfo> queryActivities(Intent intent, int flags) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags));
            }
            return pm.queryIntentActivities(intent, flags);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Package names of the activities in a query result; null when the query itself failed. */
    private static Set<String> packagesOf(List<ResolveInfo> results) {
        if (results == null) {
            return null;
        }
        Set<String> packages = new HashSet<>();
        for (ResolveInfo info : results) {
            if (info != null && info.activityInfo != null && info.activityInfo.packageName != null) {
                packages.add(info.activityInfo.packageName);
            }
        }
        return packages;
    }

    /** Include components disabled in the manifest or at runtime. */
    @SuppressWarnings("deprecation")
    private static int disabledComponentsFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? PackageManager.MATCH_DISABLED_COMPONENTS
                : PackageManager.GET_DISABLED_COMPONENTS;
    }

    @SuppressWarnings("deprecation")
    private static int signingFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    /** "pkg/cls:pkg/cls" (a flattened ComponentName list) → package names. */
    private static void addComponentPackages(String flattened, Set<String> into) {
        if (flattened == null || flattened.isEmpty()) {
            return;
        }
        for (String part : flattened.split(":")) {
            if (part.isEmpty()) {
                continue;
            }
            ComponentName component = ComponentName.unflattenFromString(part);
            if (component != null) {
                into.add(component.getPackageName());
            } else {
                int slash = part.indexOf('/');
                into.add(slash > 0 ? part.substring(0, slash) : part);
            }
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }
}
