package uz.jac.secure.android;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * The alarm a scan raises the moment it finds a threat.
 *
 * <h3>What the user sees</h3>
 *
 * One modal for however many threats a scan finds: each one a row with its
 * icon, its name, its package and what kind of threat it is, and a red
 * "Delete" that hands it to the system uninstaller (an app) or removes it (an
 * APK file). Under the list, "Details" opens the scanner's page and "Ignore"
 * closes the alarm and keeps it closed for those exact versions. A threat found
 * while the modal is up joins its list rather than stacking a second window.
 *
 * <p>With the modal comes the alarm ({@link JacAlarm}, looping until the user
 * answers, at most {@link #ALARM_MAX_MS}) and a vibration. If Humogram is not
 * on screen — the user switched away during a scan, or the phone is locked
 * behind Humogram's passcode — a high-priority heads-up notification says so
 * instead, with one pass of the same alarm; opening the app, from the
 * notification or otherwise, brings the modal up.
 *
 * <h3>Where it is wired</h3>
 *
 * {@link DeviceScanner} registers {@link #LISTENER} for every scan, so nobody
 * has to remember to: the launch scan, "Scan now" on the Humogram page and the
 * check on the welcome screen all alarm the same way. {@code LaunchActivity}
 * calls {@link #onResume} (one line), which is what shows a waiting modal once
 * the app is back in front.
 *
 * <h3>What it does not do</h3>
 *
 * It needs no account: before sign-in it works the same, and "Details" opens
 * the welcome screen's phone check instead of the Humogram page. Nothing it
 * shows leaves the phone. It never alarms twice in one process about the same
 * app version or file, and never about an app the user trusted or a threat
 * they ignored.
 */
public final class ThreatAlert {

    /** Longest the alarm loops on its own before it gives up; the modal stays. */
    private static final long ALARM_MAX_MS = 30_000L;

    private static final String CHANNEL_ID = "humogram_threats";
    private static final int NOTIFICATION_ID = 0x4A4143; // "JAC"

    private ThreatAlert() {
    }

    /** One threat waiting for the user. Exactly one of {@code app} and {@code file} is set. */
    private static final class Threat {
        final String key;
        final DeviceScanReport.AppResult app;
        final DeviceScanReport.FileResult file;
        View row;

        Threat(String key, DeviceScanReport.AppResult app, DeviceScanReport.FileResult file) {
            this.key = key;
            this.app = app;
            this.file = file;
        }

        String label() {
            return app != null ? DeviceScanUi.appLabel(app) : DeviceScanUi.fileLabel(file);
        }

        int risk() {
            return app != null ? app.risk : file.risk;
        }

        java.util.List<ScanFinding> findings() {
            return app != null ? app.findings : file.findings;
        }
    }

    // All on the UI thread.
    private static final LinkedHashMap<String, Threat> pending = new LinkedHashMap<>();
    private static final HashSet<String> alerted = new HashSet<>();
    private static AlertDialog dialog;
    private static LinearLayout rows;
    /** Set while the user is in the system uninstaller from one of our rows. */
    private static boolean leftToUninstall;

    /** Registered by {@link DeviceScanner} for every scan. */
    static final DeviceScanner.Listener LISTENER = new DeviceScanner.Listener() {
        @Override
        public void onProgress(int done, int total, String currentLabel) {
        }

        @Override
        public void onFinished(DeviceScanReport report) {
        }

        @Override
        public void onThreat(DeviceScanReport.AppResult app, DeviceScanReport.FileResult file) {
            try {
                ThreatAlert.onThreat(app, file);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    };

    /**
     * True when this threat has already been put in front of the user since the
     * app was last brought to the foreground — so the launch scan's Bulletin
     * does not say it a second time. Cleared on {@link #onAppBackgrounded()}, so
     * the modal returns on the next open.
     */
    public static boolean isHandled(Context context, String key) {
        return key != null && alerted.contains(key);
    }

    /** The key a file result is remembered by; an app's is its trust key. */
    public static String fileKey(DeviceScanReport.FileResult file) {
        return "file:" + file.path + "|" + file.size;
    }

    private static void onThreat(DeviceScanReport.AppResult app, DeviceScanReport.FileResult file) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || (app == null && file == null)) {
            return;
        }
        if (app != null && DeviceScanUi.isTrusted(context, app)) {
            return;
        }
        final String key = app != null ? app.trustKey() : fileKey(file);
        if (!alerted.add(key)) {
            return;
        }
        pending.put(key, new Threat(key, app, file));

        final Activity activity = frontActivity();
        if (activity != null) {
            show(activity);
        } else {
            notifyInBackground(context);
            JacAlarm.sound(context);
        }
    }

    /**
     * {@code LaunchActivity.onResume} and the chat list's resume: bring up the
     * threats the last scan found, now that the app is in front.
     *
     * <p>Every foreground, not once per process (the owner's call, 2026-10-16):
     * the modal must greet the user on each open — a fresh start or a return
     * from minimising alike — for as long as a threat is still there. Within one
     * foreground session it shows once (the {@code alerted} guard);
     * {@link #onAppBackgrounded()} clears that guard so the next open shows it
     * again. "Ignore" only closes it for the session.
     */
    public static void onResume(Activity activity) {
        try {
            if (activity == null || isLocked()) {
                return;
            }
            if (leftToUninstall) {
                leftToUninstall = false;
                dropRemoved(activity);
            }
            // A file/link tap-warning is up: let it finish; it flushes us when
            // it closes (QuarantineDialogs.onWarningDismissed).
            if (QuarantineDialogs.isWarningShowing()) {
                return;
            }
            presentFromLastReport(activity);
            if (!pending.isEmpty() && dialog == null) {
                show(activity);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Cleared every time the app leaves the foreground, so the next open re-alerts. */
    public static void onAppBackgrounded() {
        try {
            JacAlarm.stop();
            alerted.clear();
            if (dialog == null) {
                pending.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Queue every still-present threat from the last scan report that is not
     * already queued or shown this session. This is what makes the modal return
     * on each open without re-running a whole scan.
     */
    private static void presentFromLastReport(Context context) {
        final DeviceScanReport report = DeviceScanUi.peekReport();
        if (report == null) {
            return;
        }
        if (report.apps != null) {
            for (DeviceScanReport.AppResult app : report.apps) {
                if (app == null || app.risk < DeviceScanUi.RISK_SUSPICIOUS
                        || DeviceScanUi.isTrusted(context, app)) {
                    continue;
                }
                final String key = app.trustKey();
                if (pending.containsKey(key) || !alerted.add(key)) {
                    continue;
                }
                pending.put(key, new Threat(key, app, null));
            }
        }
        if (report.apkFiles != null) {
            for (DeviceScanReport.FileResult file : report.apkFiles) {
                if (file == null || file.risk < DeviceScanUi.RISK_SUSPICIOUS) {
                    continue;
                }
                final String key = fileKey(file);
                if (pending.containsKey(key) || !alerted.add(key)) {
                    continue;
                }
                pending.put(key, new Threat(key, null, file));
            }
        }
    }

    // ------------------------------------------------------------------
    // The modal
    // ------------------------------------------------------------------

    private static void show(Activity activity) {
        // A file/link tap-warning owns the screen and the alarm right now: wait.
        // Its dismiss flushes us (QuarantineDialogs.onWarningDismissed).
        if (QuarantineDialogs.isWarningShowing()) {
            return;
        }
        cancelNotification(activity);
        if (dialog != null && rows != null) {
            // One window: new threats join the list already on screen.
            for (Threat threat : pending.values()) {
                if (threat.row == null) {
                    addRow(activity, threat);
                }
            }
            updateTitle(activity);
            JacAlarm.startLoop(activity, ALARM_MAX_MS);
            return;
        }

        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (Threat threat : pending.values()) {
            threat.row = null;
            addRow(activity, threat);
        }

        final ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // A long list scrolls inside the dialog instead of pushing its
                // buttons off the screen.
                final int cap = Math.min(dp(320), AndroidUtilities.displaySize.y / 2);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(rows);

        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        final TextView body = new TextView(activity);
        body.setText(JacStrings.get(activity, R.string.jac_threat_alert_body));
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        body.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        content.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title(activity))
                .setView(content)
                .setPositiveButton(JacStrings.get(activity, R.string.jac_threat_details), (d, which) -> openDetails())
                .setNegativeButton(JacStrings.get(activity, R.string.jac_threat_ignore), (d, which) -> ignoreAll(activity));
        final AlertDialog created = builder.create();
        created.setCanceledOnTouchOutside(false);
        created.setOnDismissListener(d -> {
            if (dialog == created) {
                dialog = null;
                rows = null;
                JacAlarm.stop();
                if (!leftToUninstall) {
                    // Closed without an answer (back): nothing is remembered,
                    // so the next launch's scan says it again.
                    pending.clear();
                }
            }
        });
        dialog = created;
        try {
            created.show();
        } catch (Throwable t) {
            dialog = null;
            rows = null;
            FileLog.e(t);
            return;
        }
        tint(created, AlertDialog.BUTTON_NEGATIVE, Theme.key_dialogTextGray3);
        JacAlarm.startLoop(activity, ALARM_MAX_MS);
    }

    private static String title(Context context) {
        final int count = pending.size();
        return count == 1
                ? JacStrings.get(context, R.string.jac_threat_alert_title_one)
                : JacStrings.get(context, R.string.jac_threat_alert_title_many, count);
    }

    private static void updateTitle(Context context) {
        if (dialog != null) {
            dialog.setTitle(title(context));
        }
    }

    /** Icon, name, package, kind of threat, and a Delete — one row per threat. */
    private static void addRow(Activity activity, Threat threat) {
        final LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(8), dp(16), dp(8));

        final ImageView icon = new ImageView(activity);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final Drawable appIcon = threat.app != null ? appIcon(activity, threat.app.packageName) : null;
        if (appIcon != null) {
            icon.setImageDrawable(appIcon);
        } else {
            icon.setImageResource(R.drawable.msg_download);
            icon.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_text_RedRegular), PorterDuff.Mode.SRC_IN));
        }
        row.addView(icon, LayoutHelper.createLinear(40, 40, 0, 0, 12, 0));

        final LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = line(activity, threat.label(), 16, Theme.key_dialogTextBlack);
        name.setTypeface(AndroidUtilities.bold());
        texts.addView(name);
        final String pkg = threat.app != null ? threat.app.packageName
                : !TextUtils.isEmpty(threat.file.packageName)
                ? JacStrings.get(activity, R.string.jac_threat_file_installs, threat.file.packageName)
                : JacStrings.get(activity, R.string.jac_threat_file);
        texts.addView(line(activity, pkg, 13, Theme.key_dialogTextGray3));
        final ScanFinding lead = DeviceScanUi.leadFinding(threat.findings());
        final String word = DeviceScanUi.riskWord(activity, threat.risk(), lead);
        final String reason = DeviceScanUi.shortReason(activity, lead);
        texts.addView(line(activity, word != null ? word + " · " + reason : reason, 13, Theme.key_text_RedRegular));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        final boolean canDelete = threat.file != null || !threat.app.system;
        if (canDelete) {
            final TextView delete = new TextView(activity);
            delete.setText(JacStrings.get(activity, R.string.jac_threat_delete));
            delete.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            delete.setTypeface(AndroidUtilities.bold());
            delete.setTextColor(0xFFFFFFFF);
            delete.setGravity(Gravity.CENTER);
            delete.setPadding(dp(14), 0, dp(14), 0);
            delete.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16),
                    Theme.getColor(Theme.key_text_RedBold),
                    Theme.multAlpha(0xFFFFFFFF, .25f)));
            delete.setOnClickListener(v -> delete(activity, threat));
            row.addView(delete, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 8, 0, 0, 0));
        }

        threat.row = row;
        rows.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private static TextView line(Context context, CharSequence text, int sizeDp, int colorKey) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setTextColor(Theme.getColor(colorKey));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private static Drawable appIcon(Context context, String packageName) {
        try {
            return context.getPackageManager().getApplicationIcon(packageName);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // The three answers
    // ------------------------------------------------------------------

    private static void delete(Activity activity, Threat threat) {
        // The user is acting on it: the alarm has done its job.
        JacAlarm.stop();
        if (threat.app != null) {
            leftToUninstall = true;
            try {
                // ACTION_DELETE needs REQUEST_DELETE_PACKAGES (declared); the
                // system asks for the final confirmation itself.
                activity.startActivity(new Intent(Intent.ACTION_DELETE,
                        Uri.parse("package:" + threat.app.packageName)));
            } catch (Throwable t) {
                try {
                    activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + threat.app.packageName)));
                } catch (Throwable ignored) {
                    leftToUninstall = false;
                }
            }
            return;
        }
        final DeviceScanReport.FileResult file = threat.file;
        Utilities.globalQueue.postRunnable(() -> {
            final int outcome = DeviceScanSection.deleteScannedFile(file);
            AndroidUtilities.runOnUIThread(() -> {
                if (outcome == DeviceScanSection.DELETE_OK) {
                    removeRow(threat);
                    rescan(activity);
                } else {
                    try {
                        BulletinFactory.global().createErrorBulletin(JacStrings.get(activity,
                                outcome == DeviceScanSection.DELETE_CHANGED
                                        ? R.string.jac_devscan_file_changed
                                        : R.string.jac_devscan_file_delete_failed)).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        });
    }

    /** Back from the system uninstaller: drop the rows of apps that are gone. */
    private static void dropRemoved(Activity activity) {
        boolean any = false;
        final PackageManager pm = activity.getPackageManager();
        for (Iterator<Threat> it = new ArrayList<>(pending.values()).iterator(); it.hasNext(); ) {
            final Threat threat = it.next();
            if (threat.app == null) {
                continue;
            }
            try {
                pm.getPackageInfo(threat.app.packageName, 0);
            } catch (PackageManager.NameNotFoundException gone) {
                removeRow(threat);
                any = true;
            } catch (Throwable ignored) {
            }
        }
        if (any) {
            rescan(activity);
        }
    }

    private static void removeRow(Threat threat) {
        pending.remove(threat.key);
        if (rows != null && threat.row != null) {
            rows.removeView(threat.row);
        }
        threat.row = null;
        if (pending.isEmpty()) {
            if (dialog != null) {
                final AlertDialog local = dialog;
                dialog = null;
                rows = null;
                try {
                    local.dismiss();
                } catch (Throwable ignored) {
                }
            }
            JacAlarm.stop();
        } else if (dialog != null) {
            updateTitle(dialog.getContext());
        }
    }

    /** Re-check after a removal, so the page and the Settings card stop counting it. */
    private static void rescan(Context context) {
        try {
            DeviceScanner.getInstance(context.getApplicationContext()).start(DeviceScanner.TRIGGER_MANUAL, null);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void ignoreAll(Context context) {
        // Only closes it for this session, not for good (the owner's call,
        // 2026-10-16): the modal returns on the next app open while the threat
        // is still there, because nothing is persisted and the per-session
        // {@code alerted} guard is cleared on backgrounding. The one way to
        // silence it for a specific app is "Trust" on the scanner page.
        pending.clear();
        JacAlarm.stop();
    }

    /**
     * The scanner's page: the Humogram page once signed in, the welcome
     * screen's phone check before. Not pushed again when it is already open.
     */
    private static void openDetails() {
        pending.clear();
        JacAlarm.stop();
        final BaseFragment last = LaunchActivity.getLastFragment();
        if (last == null || last instanceof HumogramSettingsActivity || last instanceof PhoneCheckActivity) {
            return;
        }
        final boolean signedIn = UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated();
        last.presentFragment(signedIn ? new HumogramSettingsActivity() : new PhoneCheckActivity());
    }

    // ------------------------------------------------------------------
    // Not in front
    // ------------------------------------------------------------------

    private static Activity frontActivity() {
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null || activity.isFinishing() || ApplicationLoader.mainInterfacePaused || isLocked()) {
            return null;
        }
        return activity;
    }

    private static boolean isLocked() {
        final String hash = SharedConfig.passcodeHash;
        return SharedConfig.isWaitingForPasscodeEnter
                || (hash != null && hash.length() > 0 && SharedConfig.appLocked);
    }

    /** A heads-up notification standing in for the modal; tapping it opens the app, which shows it. */
    private static void notifyInBackground(Context context) {
        try {
            final NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                final NotificationManager system = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (system != null && system.getNotificationChannel(CHANNEL_ID) == null) {
                    final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                            JacStrings.get(context, R.string.jac_threat_channel), NotificationManager.IMPORTANCE_HIGH);
                    // The sound is JacAlarm's, played by the app on the alarm
                    // stream with the user's chosen tone; a channel's sound is
                    // fixed at creation and could not follow that choice.
                    channel.setSound(null, null);
                    channel.enableVibration(true);
                    channel.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400});
                    channel.enableLights(true);
                    channel.setLightColor(0xFFFF0000);
                    channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
                    system.createNotificationChannel(channel);
                }
            }
            final Intent open = new Intent(context, LaunchActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            final PendingIntent tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            final int count = pending.size();
            final String title = count == 1
                    ? JacStrings.get(context, R.string.jac_threat_notif_title_one, pending.values().iterator().next().label())
                    : JacStrings.get(context, R.string.jac_threat_notif_title_many, count);
            final String text = JacStrings.get(context, R.string.jac_threat_notif_text);
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setColor(0xFFD22F35)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.notification)
                            .setContentTitle(JacStrings.get(context, R.string.jac_threat_alert_title_one))
                            .build())
                    .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                    .setAutoCancel(true)
                    .setContentIntent(tap);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException noPermission) {
            // POST_NOTIFICATIONS not granted: the alarm still sounds, and the
            // modal is waiting for the next time the app is opened.
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void cancelNotification(Context context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) {
        }
    }

    private static void tint(AlertDialog dialog, int button, int colorKey) {
        final View view = dialog.getButton(button);
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(Theme.getColor(colorKey));
        }
    }
}
