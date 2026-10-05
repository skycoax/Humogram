package uz.jac.secure.android;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import uz.jac.secure.core.device.InstalledAppPolicy;
import uz.jac.secure.core.model.SignalCodes;

/**
 * Words, colours and status for the device virus scanner — shared by the
 * scanner page, the launch hook and the Settings entry that shows a status,
 * so "3 threats" in Settings cannot disagree with the page it opens.
 *
 * <h3>Findings to sentences</h3>
 *
 * The scanner reports {@link ScanFinding}s — a stable code plus positional
 * args — and never an English sentence the UI would print (see ScanFinding for
 * why). Each code has two renderings here: a <em>short</em> one of a few words
 * for the second line of a list row, and a <em>long</em> one-sentence
 * explanation for the details dialog. The file scanner's codes that the device
 * scanner reuses ({@code APK_IMPERSONATION_*}, {@code APK_SMS_AND_ACCESSIBILITY}
 * and friends) take their long sentence from {@link ScanUi#explain}, so a
 * counterfeit bank app is described in the same words whether it arrived as a
 * file in a chat or is already installed.
 *
 * <p>Arity is checked before formatting, as ScanUi does: a template that wants
 * an arg the finding does not carry falls back to a generic sentence instead of
 * throwing in the middle of a list bind.
 *
 * <h3>The last report, without main-thread I/O</h3>
 *
 * {@code DeviceScanner.getLastReport()} may read the report from disk the
 * first time. The settings rows want it on the UI thread, so this class loads
 * it once on a background queue and keeps it; every finished scan replaces it
 * (a listener is registered for the life of the process). Callers that render
 * before the first load see "…" for a few milliseconds rather than a stall.
 */
public final class DeviceScanUi {

    /** {@code AppRisk.level}, mirrored so the UI does not depend on the enum's package. */
    public static final int RISK_NONE = 0;
    public static final int RISK_NOTICE = 1;
    public static final int RISK_SUSPICIOUS = 2;
    public static final int RISK_DANGEROUS = 3;
    public static final int RISK_MALICIOUS = 4;

    private DeviceScanUi() {
    }

    // ------------------------------------------------------------------
    // The last report
    // ------------------------------------------------------------------

    private static volatile DeviceScanReport cachedReport;
    private static volatile boolean reportLoaded;
    private static final AtomicBoolean watching = new AtomicBoolean();

    /** Keeps {@link #cachedReport} current for every scan, whoever started it. */
    private static final DeviceScanner.Listener CACHE_KEEPER = new DeviceScanner.Listener() {
        @Override
        public void onProgress(int done, int total, String currentLabel) {
        }

        @Override
        public void onFinished(DeviceScanReport report) {
            remember(report);
        }
    };

    /** The newest report this process has seen, or null. UI thread. */
    public static DeviceScanReport peekReport() {
        return cachedReport;
    }

    /** False until the report on disk has been looked for at least once. */
    public static boolean isReportLoaded() {
        return reportLoaded;
    }

    /** Record a finished scan. Older reports never replace newer ones. */
    public static void remember(DeviceScanReport report) {
        if (report == null) {
            return;
        }
        final DeviceScanReport current = cachedReport;
        if (current == null || report.finishedAt >= current.finishedAt) {
            cachedReport = report;
        }
        reportLoaded = true;
    }

    /**
     * Make sure the last report is loaded, then run {@code onLoaded} on the UI
     * thread. Runs it immediately when the report is already known.
     */
    public static void loadReport(Context context, Runnable onLoaded) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        ensureWatching(app);
        if (reportLoaded) {
            if (onLoaded != null) {
                onLoaded.run();
            }
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            DeviceScanReport loaded = null;
            try {
                loaded = DeviceScanner.getInstance(app).getLastReport();
            } catch (Throwable t) {
                FileLog.e(t);
            }
            final DeviceScanReport result = loaded;
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null) {
                    remember(result);
                } else {
                    reportLoaded = true;
                }
                if (onLoaded != null) {
                    try {
                        onLoaded.run();
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                }
            });
        });
    }

    static void ensureWatching(Context context) {
        if (context == null || !watching.compareAndSet(false, true)) {
            return;
        }
        try {
            DeviceScanner.getInstance(context.getApplicationContext()).addListener(CACHE_KEEPER);
        } catch (Throwable t) {
            watching.set(false);
            FileLog.e(t);
        }
    }

    // ------------------------------------------------------------------
    // Settings state, as the user sees it
    // ------------------------------------------------------------------

    /**
     * Whether launch scans are on in the sense the user chose: the switch is
     * on and they did not answer "Not now" to the launch notice.
     */
    public static boolean isOnLaunchEnabled(Context context) {
        return HumogramConfig.isDeviceScanOnLaunch(context)
                && HumogramConfig.getDeviceScanConsent(context) != 0;
    }

    public static boolean isTrusted(Context context, DeviceScanReport.AppResult app) {
        return app != null && HumogramConfig.isAppTrusted(context, app.trustKey());
    }

    /**
     * A run that stopped before it saw anything (cancelled, engine missing,
     * crashed) and had no earlier good report to fall back on. It must not
     * read as "no threats": it checked nothing.
     */
    public static boolean isEmptyFailure(DeviceScanReport report) {
        return report != null && report.error != null && report.appsChecked <= 0
                && (report.apps == null || report.apps.isEmpty())
                && (report.apkFiles == null || report.apkFiles.isEmpty());
    }

    /**
     * The value-column word for a row that opens the scanner: threats in red
     * or amber, otherwise "No threats", "Off", "Not checked", or "Checking…"
     * while one runs.
     *
     * <p>Threats are shown even when launch scans are off — a known threat
     * outranks a setting.
     */
    public static CharSequence statusText(Context context) {
        if (context == null) {
            return null;
        }
        if (!ScannerBootstrap.isInstalled()) {
            return JacStrings.get(context, R.string.jac_devscan_status_unavailable);
        }
        try {
            if (DeviceScanner.getInstance(context.getApplicationContext()).isRunning()) {
                return JacStrings.get(context, R.string.jac_devscan_status_scanning);
            }
        } catch (Throwable ignored) {
        }
        if (!reportLoaded) {
            return "…";
        }
        final DeviceScanReport report = cachedReport;
        if (report != null) {
            final int threats = report.threatCount(context);
            if (threats > 0) {
                return coloured(JacStrings.get(context, R.string.jac_devscan_status_threats, threats),
                        riskColorKey(Math.max(RISK_SUSPICIOUS, report.worstRisk(context))));
            }
        }
        if (!isOnLaunchEnabled(context)) {
            return JacStrings.get(context, R.string.jac_devscan_status_off);
        }
        if (report == null || isEmptyFailure(report)) {
            return JacStrings.get(context, R.string.jac_devscan_status_never);
        }
        return JacStrings.get(context, R.string.jac_devscan_status_none);
    }

    // ------------------------------------------------------------------
    // Risk words and colours
    // ------------------------------------------------------------------

    /**
     * Red for what we are sure of or what can empty a bank account, amber for
     * "looks off", no colour below that — the same two-colour scale as the
     * file verdicts. Returns -1 for "no colour".
     */
    public static int riskColorKey(int risk) {
        if (risk >= RISK_DANGEROUS) {
            return Theme.key_text_RedRegular;
        }
        if (risk == RISK_SUSPICIOUS) {
            return Theme.key_color_orange;
        }
        return -1;
    }

    /**
     * The risk word, chosen by the lead finding as well as the level.
     *
     * <p>{@code lead} is not optional by design: every caller has the findings
     * in hand, and passing null would quietly bring back the bug this parameter
     * exists to fix.
     *
     * <p>MALICIOUS is reached two ways, and the two do not deserve the same
     * word. A hash that matches threat intel is a virus. An app whose package id merely looks like a bank's, scored up
     * by heuristics, is a counterfeit — calling it a virus states a detection
     * that did not happen, about (often) a real publisher's real app.
     */
    public static String riskWord(Context context, int risk, ScanFinding lead) {
        switch (risk) {
            case RISK_MALICIOUS:
                return isImpersonation(lead)
                        ? JacStrings.get(context, R.string.jac_devscan_risk_counterfeit)
                        : JacStrings.get(context, R.string.jac_devscan_risk_malicious);
            case RISK_DANGEROUS:
                return JacStrings.get(context, R.string.jac_devscan_risk_dangerous);
            case RISK_SUSPICIOUS:
                return JacStrings.get(context, R.string.jac_devscan_risk_suspicious);
            case RISK_NOTICE:
                return JacStrings.get(context, R.string.jac_devscan_risk_notice);
            default:
                return null;
        }
    }

    /** One of the three "pretends to be a known brand" findings. */
    static boolean isImpersonation(ScanFinding finding) {
        if (finding == null) {
            return false;
        }
        return SignalCodes.APK_IMPERSONATION_CERT_MISMATCH.equals(finding.code)
                || SignalCodes.APK_IMPERSONATION_PACKAGE_LOOKALIKE.equals(finding.code)
                || SignalCodes.APK_IMPERSONATION_LABEL.equals(finding.code);
    }

    /**
     * The risk word for a row's value column, coloured with a span — the one
     * way to reach a TextCell's value text. {@code UItem.red()} recolours the
     * title and icon, and {@code setTextAndValue} resets the value colour on
     * every bind, so only a span inside the text survives.
     */
    public static CharSequence riskValue(Context context, int risk, ScanFinding lead) {
        final String word = riskWord(context, risk, lead);
        return word == null ? null : coloured(word, riskColorKey(risk));
    }

    static CharSequence coloured(String text, int colorKey) {
        if (text == null || colorKey < 0) {
            return text;
        }
        final SpannableString span = new SpannableString(text);
        span.setSpan(new ForegroundColorSpan(Theme.getColor(colorKey)), 0, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    static String appLabel(DeviceScanReport.AppResult app) {
        if (app.label != null && !app.label.trim().isEmpty()) {
            return app.label.trim();
        }
        return app.packageName;
    }

    static String fileLabel(DeviceScanReport.FileResult file) {
        if (file.name != null && !file.name.isEmpty()) {
            return file.name;
        }
        return file.path;
    }

    /**
     * Where an app came from, in words: "Installed from Google Play",
     * "Installed from a file", "Installed by com.some.dropper", …
     *
     * <p>An app installed <em>by another app</em> that is not a store is the
     * signature of a dropper, so the installer's package name is shown as-is
     * rather than hidden behind "unknown".
     */
    static String sourceText(Context context, DeviceScanReport.AppResult app) {
        if (app.system) {
            return JacStrings.get(context, R.string.jac_devscan_source_system);
        }
        final String store = storeName(app.installer);
        if (store != null) {
            return JacStrings.get(context, R.string.jac_devscan_source_store, store);
        }
        if (app.store) {
            // A store we have no display name for, or Android's own
            // "installed by a store" flag with the store itself not visible.
            return app.installer != null && !app.installer.isEmpty()
                    ? JacStrings.get(context, R.string.jac_devscan_source_store, app.installer)
                    : JacStrings.get(context, R.string.jac_devscan_source_store_generic);
        }
        if (app.installer == null || app.installer.isEmpty()) {
            return JacStrings.get(context, R.string.jac_devscan_source_unknown);
        }
        if (InstalledAppPolicy.PACKAGE_INSTALLERS.contains(app.installer)) {
            // The core's own list, not a copy of it: a fourth copy of the four
            // system installers is a fourth place to forget to update.
            return JacStrings.get(context, R.string.jac_devscan_source_file);
        }
        return JacStrings.get(context, R.string.jac_devscan_source_app, app.installer);
    }

    /**
     * Display names of the stores {@code InstalledAppPolicy.DEFAULT_STORES}
     * counts. Keep in step with that set and with the {@code <queries>} block in
     * AndroidManifest.xml.
     */
    private static final Map<String, String> STORE_NAMES = new HashMap<>();

    static {
        // Brand names, not translated: they are what the store calls itself.
        STORE_NAMES.put("com.android.vending", "Google Play");
        STORE_NAMES.put("com.sec.android.app.samsungapps", "Galaxy Store");
        STORE_NAMES.put("com.huawei.appmarket", "AppGallery");
        STORE_NAMES.put("com.xiaomi.market", "GetApps");
        STORE_NAMES.put("com.xiaomi.mipicks", "GetApps");
        STORE_NAMES.put("com.heytap.market", "App Market");
        STORE_NAMES.put("com.oppo.market", "App Market");
        // Both vivo stores, deliberately the same label: com.bbk.appstore is the
        // China build (BBK is vivo's parent), com.vivo.appstore the global one.
        STORE_NAMES.put("com.bbk.appstore", "V-Appstore");
        STORE_NAMES.put("com.vivo.appstore", "V-Appstore");
        // Not "AppGallery": Honor split from Huawei in 2020 and ships its own
        // store under its own name, so calling it AppGallery would name the
        // wrong company's shop to the user.
        STORE_NAMES.put("com.hihonor.appmarket", "HONOR App Market");
        STORE_NAMES.put("com.amazon.venezia", "Amazon Appstore");
        STORE_NAMES.put("ru.vk.store", "RuStore");
    }

    static String storeName(String installer) {
        return installer == null ? null : STORE_NAMES.get(installer);
    }

    // ------------------------------------------------------------------
    // Findings
    // ------------------------------------------------------------------

    /**
     * Findings, most decision-changing first — the same principle as
     * ScanUi's PRIORITY. "Known malware" beats everything; a counterfeit of a
     * bank app beats a capability; a capability that is <em>on</em>
     * (accessibility enabled, SMS role held) beats one merely requested; and
     * "installed outside a store" — true of many honest apps — comes last.
     */
    private static final List<String> PRIORITY = Arrays.asList(
            SignalCodes.DEV_KNOWN_MALWARE,
            SignalCodes.APK_IMPERSONATION_CERT_MISMATCH,
            SignalCodes.APK_IMPERSONATION_PACKAGE_LOOKALIKE,
            SignalCodes.APK_IMPERSONATION_LABEL,
            // Wearing the store's own package id is an impersonation, not a
            // provenance note: it belongs with the counterfeits above, and it
            // must outrank DEV_SIDELOADED at the bottom of this list. A code
            // absent from PRIORITY loses leadFinding to every code present,
            // so a HIGH finding left off here is reported as "not from an app
            // store" -- the mildest thing we could have said about a dropper.
            SignalCodes.DEV_FAKE_STORE_PACKAGE,
            // An installer built so that scanners cannot open it. Nothing
            // honest is packed that way, so it leads over any capability.
            SignalCodes.APK_EVASIVE_STRUCTURE,
            SignalCodes.DEV_OVERLAY_ACCESSIBILITY,
            SignalCodes.DEV_ACCESSIBILITY_ACTIVE,
            SignalCodes.DEV_SMS_ROLE,
            SignalCodes.DEV_SMS_ACCESS,
            SignalCodes.APK_REQUESTS_SMS,
            SignalCodes.DEV_DEVICE_ADMIN_ACTIVE,
            SignalCodes.DEV_NOTIFICATION_ACCESS,
            SignalCodes.DEV_HIDDEN_ICON,
            SignalCodes.DEV_USSD_COMBO,
            SignalCodes.APK_DANGEROUS_PERMISSIONS,
            SignalCodes.APK_SMS_AND_ACCESSIBILITY,
            SignalCodes.APK_OVERLAY_COMBO,
            SignalCodes.APK_DEVICE_ADMIN,
            SignalCodes.DEV_DROPPER,
            SignalCodes.APK_REQUEST_INSTALL_PACKAGES,
            SignalCodes.DEV_JUNK_PACKAGE,
            SignalCodes.DEV_BLANK_LABEL,
            SignalCodes.APK_UNSIGNED,
            SignalCodes.APK_DEBUGGABLE,
            // Three statements about where the app came from, strongest first,
            // all above the plain "not from a store" they refine. Carrying a
            // bank's own package id from outside a store is deliberately NOT in
            // the counterfeit block at the top: we hold no certificate pin for
            // these brands, so it is an origin fact, and a capability that is
            // switched on right now is the more useful thing to lead with.
            SignalCodes.DEV_BRAND_PACKAGE_SIDELOADED,
            SignalCodes.DEV_STORE_CLAIM_ORPHANED,
            SignalCodes.DEV_SIDELOADED
    );

    /**
     * Code to copy. {@code longRes == 0} means "the file scanner already has
     * the sentence" — {@link ScanUi#explain} supplies it.
     */
    private static final Map<String, Template> TEMPLATES = new HashMap<>();

    private static final class Template {
        final int shortRes;
        final int shortArgs;
        final int longRes;
        final int longArgs;

        Template(int shortRes, int shortArgs, int longRes, int longArgs) {
            this.shortRes = shortRes;
            this.shortArgs = shortArgs;
            this.longRes = longRes;
            this.longArgs = longArgs;
        }
    }

    static {
        // [family]
        TEMPLATES.put(SignalCodes.DEV_KNOWN_MALWARE, new Template(
                R.string.jac_devscan_short_known_malware, 1, R.string.jac_devscan_reason_dev_known_malware, 1));
        TEMPLATES.put(SignalCodes.DEV_ACCESSIBILITY_ACTIVE, new Template(
                R.string.jac_devscan_short_accessibility, 0, R.string.jac_devscan_reason_dev_accessibility_active, 0));
        TEMPLATES.put(SignalCodes.DEV_SMS_ROLE, new Template(
                R.string.jac_devscan_short_sms_role, 0, R.string.jac_devscan_reason_dev_sms_role, 0));
        TEMPLATES.put(SignalCodes.DEV_SMS_ACCESS, new Template(
                R.string.jac_devscan_short_sms_access, 0, R.string.jac_devscan_reason_dev_sms_access, 0));
        TEMPLATES.put(SignalCodes.DEV_NOTIFICATION_ACCESS, new Template(
                R.string.jac_devscan_short_notifications, 0, R.string.jac_devscan_reason_dev_notification_access, 0));
        TEMPLATES.put(SignalCodes.DEV_DEVICE_ADMIN_ACTIVE, new Template(
                R.string.jac_devscan_short_device_admin, 0, R.string.jac_devscan_reason_dev_device_admin_active, 0));
        TEMPLATES.put(SignalCodes.DEV_HIDDEN_ICON, new Template(
                R.string.jac_devscan_short_hidden_icon, 0, R.string.jac_devscan_reason_dev_hidden_icon, 0));
        TEMPLATES.put(SignalCodes.DEV_OVERLAY_ACCESSIBILITY, new Template(
                R.string.jac_devscan_short_overlay, 0, R.string.jac_devscan_reason_dev_overlay_accessibility, 0));
        TEMPLATES.put(SignalCodes.DEV_USSD_COMBO, new Template(
                R.string.jac_devscan_short_ussd, 0, R.string.jac_devscan_reason_dev_ussd_combo, 0));
        TEMPLATES.put(SignalCodes.DEV_DROPPER, new Template(
                R.string.jac_devscan_short_installs_apps, 0, R.string.jac_devscan_reason_dev_dropper, 0));
        TEMPLATES.put(SignalCodes.DEV_JUNK_PACKAGE, new Template(
                R.string.jac_devscan_short_junk_name, 0, R.string.jac_devscan_reason_dev_junk_package, 0));
        TEMPLATES.put(SignalCodes.DEV_BLANK_LABEL, new Template(
                R.string.jac_devscan_short_no_name, 0, R.string.jac_devscan_reason_dev_blank_label, 0));
        // [how] — three sentences, chosen in longReason()
        TEMPLATES.put(SignalCodes.DEV_STORE_CLAIM_ORPHANED, new Template(
                R.string.jac_devscan_short_store_claim_orphaned, 0,
                R.string.jac_devscan_reason_dev_store_claim_orphaned, 0));
        TEMPLATES.put(SignalCodes.DEV_SIDELOADED, new Template(
                R.string.jac_devscan_short_sideloaded, 0, R.string.jac_devscan_reason_dev_sideloaded, 1));

        // [origin] Both are statements about where the app came from, never
        // about its signature -- we hold no certificate pin for these brands,
        // so the copy must not let the user read "counterfeit" into it.
        TEMPLATES.put(SignalCodes.DEV_FAKE_STORE_PACKAGE, new Template(
                R.string.jac_devscan_short_fake_store, 0,
                R.string.jac_devscan_reason_dev_fake_store_package, 0));
        TEMPLATES.put(SignalCodes.DEV_BRAND_PACKAGE_SIDELOADED, new Template(
                R.string.jac_devscan_short_brand_sideloaded, 1,
                R.string.jac_devscan_reason_dev_brand_package_sideloaded, 1));

        // The file scanner's codes, reused for installed apps and loose APKs.
        // Long sentences come from ScanUi (longRes 0), so both scanners word a
        // counterfeit identically.
        TEMPLATES.put(SignalCodes.APK_IMPERSONATION_CERT_MISMATCH, new Template(
                R.string.jac_devscan_short_impersonation, 1, 0, 1));
        // …except this one. ScanUi's sentence for it is jac_reason_fake_bank,
        // "is not signed by %1$s. It is a counterfeit." — a claim about the
        // signature. This code only compares package ids, and no brand in
        // uz-brand-allowlist.json is certificate-pinned, so that comparison
        // never happened. Say what was actually found instead; CERT_MISMATCH,
        // where a pinned certificate really was compared, keeps the original.
        TEMPLATES.put(SignalCodes.APK_IMPERSONATION_PACKAGE_LOOKALIKE, new Template(
                R.string.jac_devscan_short_impersonation, 1,
                R.string.jac_devscan_reason_impersonation_package, 1));
        TEMPLATES.put(SignalCodes.APK_IMPERSONATION_LABEL, new Template(
                R.string.jac_devscan_short_impersonation, 1, 0, 1));
        TEMPLATES.put(SignalCodes.APK_DANGEROUS_PERMISSIONS, new Template(
                R.string.jac_devscan_short_sms_accessibility, 0, 0, 0));
        TEMPLATES.put(SignalCodes.APK_SMS_AND_ACCESSIBILITY, new Template(
                R.string.jac_devscan_short_sms_accessibility, 0, 0, 0));
        TEMPLATES.put(SignalCodes.APK_OVERLAY_COMBO, new Template(
                R.string.jac_devscan_short_overlay, 0, 0, 0));
        TEMPLATES.put(SignalCodes.APK_DEVICE_ADMIN, new Template(
                R.string.jac_devscan_short_device_admin, 0, 0, 0));
        TEMPLATES.put(SignalCodes.APK_UNSIGNED, new Template(
                R.string.jac_devscan_short_unsigned, 0, 0, 0));
        // No file-scanner sentence exists for these two, so they have their own.
        TEMPLATES.put(SignalCodes.APK_DEBUGGABLE, new Template(
                R.string.jac_devscan_short_debuggable, 0, R.string.jac_devscan_reason_apk_debuggable, 0));
        TEMPLATES.put(SignalCodes.APK_REQUEST_INSTALL_PACKAGES, new Template(
                R.string.jac_devscan_short_installs_apps, 0, R.string.jac_devscan_reason_apk_request_install_packages, 0));
        // Loose installers only: what the file asks for and how it is packed.
        TEMPLATES.put(SignalCodes.APK_EVASIVE_STRUCTURE, new Template(
                R.string.jac_devscan_short_evasive, 0, R.string.jac_devscan_reason_apk_evasive_structure, 0));
        TEMPLATES.put(SignalCodes.APK_REQUESTS_SMS, new Template(
                R.string.jac_devscan_short_requests_sms, 0, R.string.jac_devscan_reason_apk_requests_sms, 0));
    }

    /** The finding to lead with, or null for none. */
    static ScanFinding leadFinding(List<ScanFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return null;
        }
        for (String code : PRIORITY) {
            for (ScanFinding finding : findings) {
                if (code.equals(finding.code)) {
                    return finding;
                }
            }
        }
        return findings.get(0);
    }

    static boolean has(List<ScanFinding> findings, String code) {
        if (findings == null) {
            return false;
        }
        for (ScanFinding finding : findings) {
            if (code.equals(finding.code)) {
                return true;
            }
        }
        return false;
    }

    /** A few words for a row's second line. Never null. */
    static String shortReason(Context context, ScanFinding finding) {
        final Template template = finding == null ? null : TEMPLATES.get(finding.code);
        if (template == null) {
            return JacStrings.get(context, R.string.jac_devscan_short_unusual);
        }
        if (template.shortArgs == 0) {
            return JacStrings.get(context, template.shortRes);
        }
        if (finding.args.length < template.shortArgs) {
            return JacStrings.get(context, R.string.jac_devscan_short_unusual);
        }
        return JacStrings.get(context, template.shortRes, (Object[]) copyArgs(finding.args, template.shortArgs));
    }

    /** One sentence for the details dialog. Never null. */
    static String longReason(Context context, ScanFinding finding) {
        final String generic = JacStrings.get(context, R.string.jac_devscan_reason_generic);
        if (finding == null) {
            return generic;
        }
        final Template template = TEMPLATES.get(finding.code);
        if (template == null) {
            return generic;
        }
        if (template.longRes == 0) {
            final String sentence = ScanUi.explain(context, finding, null);
            return sentence != null ? sentence : generic;
        }
        if (SignalCodes.DEV_SIDELOADED.equals(finding.code)) {
            final String how = finding.arg(0);
            if (how == null || InstalledAppPolicy.HOW_UNKNOWN.equals(how)) {
                return JacStrings.get(context, R.string.jac_devscan_reason_dev_sideloaded_unknown);
            }
            if (InstalledAppPolicy.HOW_FILE.equals(how)) {
                return JacStrings.get(context, R.string.jac_devscan_reason_dev_sideloaded_file);
            }
            return JacStrings.get(context, R.string.jac_devscan_reason_dev_sideloaded, how);
        }
        if (template.longArgs == 0) {
            return JacStrings.get(context, template.longRes);
        }
        if (finding.args.length < template.longArgs) {
            return generic;
        }
        return JacStrings.get(context, template.longRes, (Object[]) copyArgs(finding.args, template.longArgs));
    }

    /** Every finding as a sentence, most important first, without repeats. */
    static List<String> longReasons(Context context, List<ScanFinding> findings) {
        final LinkedHashSet<String> out = new LinkedHashSet<>();
        if (findings == null || findings.isEmpty()) {
            return new ArrayList<>(out);
        }
        final ArrayList<ScanFinding> ordered = new ArrayList<>(findings.size());
        for (String code : PRIORITY) {
            for (ScanFinding finding : findings) {
                if (code.equals(finding.code)) {
                    ordered.add(finding);
                }
            }
        }
        for (ScanFinding finding : findings) {
            if (!ordered.contains(finding)) {
                ordered.add(finding);
            }
        }
        for (ScanFinding finding : ordered) {
            final String sentence = longReason(context, finding);
            if (!TextUtils.isEmpty(sentence)) {
                // Two impersonation codes share one sentence; say it once.
                out.add(sentence);
            }
        }
        return new ArrayList<>(out);
    }

    private static String[] copyArgs(String[] args, int count) {
        final String[] out = new String[count];
        System.arraycopy(args, 0, out, 0, count);
        return out;
    }

    // ------------------------------------------------------------------
    // Status rows
    // ------------------------------------------------------------------

    /**
     * Keeps a settings row's scanner status current: loads the last report
     * off the main thread once, refreshes when a scan starts and when it
     * finishes. {@code refresh} runs on the UI thread; attach in createView,
     * detach in onFragmentDestroy.
     */
    static final class StatusWatcher implements DeviceScanner.Listener {

        private final Runnable refresh;
        private DeviceScanner scanner;
        private boolean running;

        StatusWatcher(Runnable refresh) {
            this.refresh = refresh;
        }

        void attach(Context context) {
            if (context == null || scanner != null) {
                return;
            }
            try {
                scanner = DeviceScanner.getInstance(context.getApplicationContext());
                scanner.addListener(this);
            } catch (Throwable t) {
                scanner = null;
                FileLog.e(t);
            }
            loadReport(context, refresh);
        }

        void detach() {
            final DeviceScanner local = scanner;
            scanner = null;
            if (local != null) {
                try {
                    local.removeListener(this);
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void onProgress(int done, int total, String currentLabel) {
            if (!running) {
                // The first tick is the start: flip the row to "Checking…".
                running = true;
                refresh.run();
            }
        }

        @Override
        public void onFinished(DeviceScanReport report) {
            running = false;
            remember(report);
            refresh.run();
        }
    }
}
