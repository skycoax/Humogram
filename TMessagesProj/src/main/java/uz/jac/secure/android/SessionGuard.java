package uz.jac.secure.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TypefaceSpan;
import org.telegram.ui.LaunchActivity;

import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What the user is told when Telegram itself shuts them out — and a way to
 * tell the owner about it.
 *
 * <h3>A session Telegram ended</h3>
 *
 * Upstream drops a session the server has revoked straight back to the phone
 * number screen, without a word. People take that for a Humogram bug and log
 * in again — or, when it was a thief who ended it, lose the minutes that
 * matter. So when the server ends the session ({@code ConnectionsManager.onLogout},
 * which only the network layer calls, never "Log out" in Settings), the user
 * gets a dialog with the reason Telegram gave on the request that failed
 * (401: SESSION_REVOKED, AUTH_KEY_UNREGISTERED, SESSION_EXPIRED,
 * USER_DEACTIVATED…), what usually causes it, and what to do if it was not
 * them.
 *
 * <p>The event is kept on the phone (the last few: when, the reason, the app
 * build — nothing about the account). "Share" sends it through any app the
 * user picks; "Send feedback" in Settings opens the chat with the owner with
 * it already typed in, for the user to send or delete. Nothing goes anywhere
 * by itself.
 *
 * <h3>Sign-in paused</h3>
 *
 * After too many attempts Telegram answers sign-in with FLOOD_WAIT_n (or
 * PHONE_NUMBER_FLOOD, PHONE_PASSWORD_FLOOD) and upstream says "Too many
 * attempts, please try again later". Here that alert becomes a countdown to
 * when trying again makes sense — and says the part people need to hear:
 * scammers trigger exactly this on purpose, requesting codes for someone's
 * number until Telegram locks the owner out.
 *
 * <p>Both are matched on what the network layer already hands to every
 * request's callback; nothing is asked of the server.
 */
public final class SessionGuard {

    private static final String KEY_LOG = "session_end_log";
    private static final String KEY_OFFERED = "session_end_offered";
    private static final int MAX_LOG = 5;
    /** A logout this recent is offered with "Send feedback". */
    private static final long FEEDBACK_WINDOW_MS = 30L * 24 * 60 * 60 * 1000;
    /** How long a 401's reason is taken to belong to the logout that follows it. */
    private static final long REASON_WINDOW_MS = 60 * 1000;
    /** How long a sign-in flood is taken to belong to the alert that follows it. */
    private static final long FLOOD_WINDOW_MS = 5 * 1000;
    /** Time for the reason to arrive, and for upstream to put up its sign-in screen. */
    private static final long SHOW_DELAY_MS = 1500;

    private static final Object LOCK = new Object();
    // Guarded by LOCK.
    private static final String[] reasons = new String[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] reasonsAt = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static String floodText;
    private static long floodAt;
    /** A logout waiting for a screen to be told on: its account's name, or "" when unknown. */
    private static String pendingName;
    private static int pendingAccount = -1;

    private static WeakReference<AlertDialog> showing;

    private SessionGuard() {
    }

    // ------------------------------------------------------------------
    // From the network layer
    // ------------------------------------------------------------------

    /**
     * From {@code ConnectionsManager}'s response dispatch, for every error a
     * request gets. Any thread; cheap; never throws.
     */
    public static void noteError(int account, TLRPC.TL_error error) {
        try {
            if (error == null || error.text == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            if (error.code == 401) {
                synchronized (LOCK) {
                    reasons[account] = error.text;
                    reasonsAt[account] = now;
                }
            } else if (isFlood(error.text) && !UserConfig.getInstance(account).isClientActivated()) {
                // Only while signing in: an activated account's floods are upstream's business.
                synchronized (LOCK) {
                    floodText = error.text;
                    floodAt = now;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isFlood(String text) {
        return text.startsWith("FLOOD_WAIT_") || text.startsWith("FLOOD_PREMIUM_WAIT_")
                || text.equals("PHONE_NUMBER_FLOOD") || text.equals("PHONE_PASSWORD_FLOOD");
    }

    /**
     * From {@code ConnectionsManager.onLogout}: the server has ended this
     * account's session (the user's own "Log out" never comes here). UI thread,
     * before upstream clears the account.
     */
    public static void onServerLogout(int account) {
        try {
            String name = "";
            try {
                final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
                if (user != null) {
                    name = UserObject.getUserName(user);
                }
            } catch (Throwable ignored) {
            }
            synchronized (LOCK) {
                pendingName = name;
                pendingAccount = account;
            }
            // The 401 that caused this lands on its request's callback a moment
            // later; upstream needs a moment to put the sign-in screen up.
            AndroidUtilities.runOnUIThread(() -> showPending(LaunchActivity.instance), SHOW_DELAY_MS);
        } catch (Throwable t) {
            FileLog.e("session guard: logout note failed: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // The session-ended dialog
    // ------------------------------------------------------------------

    /**
     * Tell a logout that is waiting to be told, if there is one: from the
     * delayed post above, and from {@code LaunchActivity.onResume} for a
     * logout noticed while no screen was up. UI thread.
     */
    public static void showPending(Activity activity) {
        try {
            final String name;
            final int account;
            final String reason;
            synchronized (LOCK) {
                if (pendingAccount < 0) {
                    return;
                }
                account = pendingAccount;
                name = pendingName;
                final long age = SystemClock.elapsedRealtime() - reasonsAt[account];
                reason = reasons[account] != null && age >= 0 && age < REASON_WINDOW_MS ? reasons[account] : null;
            }
            if (!isAlive(activity)) {
                // Kept for onResume.
                return;
            }
            final AlertDialog current = showing != null ? showing.get() : null;
            if (current != null && current.isShowing()) {
                return;
            }
            synchronized (LOCK) {
                pendingAccount = -1;
                pendingName = null;
                reasons[account] = null;
            }
            final long at = System.currentTimeMillis();
            record(activity, at, reason);
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTopImage(LinkGuardUi.glyph(JacIcons.Glyph.WARNING), LinkGuardUi.bandColor())
                    .setTitle(JacStrings.get(activity, R.string.jac_session_end_title))
                    .setMessage(endMessage(activity, name, reason))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .setNegativeButton(JacStrings.get(activity, R.string.jac_session_share), (d, w) ->
                            share(activity, reportLine(activity, at, reason)))
                    .create();
            dialog.show();
            showing = new WeakReference<>(dialog);
        } catch (Throwable t) {
            FileLog.e("session guard: dialog failed: " + t.getClass().getSimpleName());
        }
    }

    private static CharSequence endMessage(Context context, String name, String reason) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        if (name != null && !name.isEmpty()) {
            final int at = out.length();
            out.append(name);
            out.setSpan(new TypefaceSpan(AndroidUtilities.bold()), at, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append("\n\n");
        }
        final boolean gone = reason != null && reason.startsWith("USER_DEACTIVATED");
        final int why;
        if (reason == null) {
            why = R.string.jac_session_end_unknown;
        } else if (reason.equals("SESSION_REVOKED")) {
            why = R.string.jac_session_end_revoked;
        } else if (reason.equals("SESSION_EXPIRED")) {
            why = R.string.jac_session_end_expired;
        } else if (gone) {
            why = R.string.jac_session_end_deactivated;
        } else {
            why = R.string.jac_session_end_unknown;
        }
        out.append(JacStrings.get(context, why));
        if (!gone) {
            out.append("\n\n").append(JacStrings.get(context, R.string.jac_session_end_advice));
        }
        out.append("\n\n").append(JacStrings.get(context, R.string.jac_session_end_saved));
        return out;
    }

    // ------------------------------------------------------------------
    // The record, and sending it
    // ------------------------------------------------------------------

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(HumogramConfig.PREFS, Context.MODE_PRIVATE);
    }

    private static void record(Context context, long at, String reason) {
        try {
            final SharedPreferences p = prefs(context);
            JSONArray log;
            try {
                log = new JSONArray(p.getString(KEY_LOG, "[]"));
            } catch (Throwable broken) {
                log = new JSONArray();
            }
            final JSONObject entry = new JSONObject();
            entry.put("t", at);
            entry.put("r", reason != null ? reason : "");
            entry.put("v", LinkGuard.versionCode(context));
            final JSONArray next = new JSONArray();
            next.put(entry);
            for (int i = 0; i < log.length() && next.length() < MAX_LOG; i++) {
                next.put(log.get(i));
            }
            p.edit().putString(KEY_LOG, next.toString()).remove(KEY_OFFERED).apply();
        } catch (Throwable t) {
            FileLog.e("session guard: record failed: " + t.getClass().getSimpleName());
        }
    }

    /** One line a support person can read: build, phone, when, Telegram's word for it. No account data. */
    private static String reportLine(Context context, long at, String reason) {
        final String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(at));
        return JacStrings.get(context, R.string.jac_session_report, "Humogram " + BuildVars.BUILD_VERSION_STRING
                + " (" + LinkGuard.versionCode(context) + "), Android " + Build.VERSION.RELEASE + ", "
                + Build.MANUFACTURER + " " + Build.MODEL, when, reason != null && !reason.isEmpty() ? reason : "?");
    }

    private static void share(Activity activity, String text) {
        try {
            final Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            activity.startActivity(Intent.createChooser(send, JacStrings.get(activity, R.string.jac_session_share)));
        } catch (Throwable t) {
            FileLog.e("session guard: share failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * For "Send feedback": {@code ?text=…} with the last logout, when there is
     * one from the last month not offered yet — the chat opens with it typed
     * in, and the user sends it or deletes it. Otherwise "". Marks it offered.
     */
    public static String feedbackQuery(Context context) {
        try {
            final SharedPreferences p = prefs(context);
            final JSONArray log = new JSONArray(p.getString(KEY_LOG, "[]"));
            if (log.length() == 0) {
                return "";
            }
            final JSONObject last = log.getJSONObject(0);
            final long at = last.optLong("t");
            if (p.getLong(KEY_OFFERED, 0) == at || System.currentTimeMillis() - at > FEEDBACK_WINDOW_MS) {
                return "";
            }
            p.edit().putLong(KEY_OFFERED, at).apply();
            final String reason = last.optString("r");
            return "?text=" + URLEncoder.encode(reportLine(context, at, reason.isEmpty() ? null : reason), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Sign-in paused
    // ------------------------------------------------------------------

    /**
     * From {@code LoginActivity.needShowAlert}: when the alert about to go up
     * is upstream's "too many attempts" for a flood just noted, put up the
     * countdown instead and return true. UI thread.
     */
    public static boolean replaceLoginAlert(BaseFragment fragment, String text) {
        try {
            final String flood;
            synchronized (LOCK) {
                if (floodText == null || SystemClock.elapsedRealtime() - floodAt > FLOOD_WINDOW_MS) {
                    return false;
                }
                flood = floodText;
            }
            final Activity activity = fragment != null ? fragment.getParentActivity() : null;
            if (text == null || !isAlive(activity) || !isUpstreamFloodText(text)) {
                return false;
            }
            synchronized (LOCK) {
                floodText = null;
            }
            showPause(fragment, activity, flood);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Upstream's words for a flood: "…try again later", "…try again in %s", the number one. */
    private static boolean isUpstreamFloodText(String text) {
        if (text.equals(LocaleController.getString(R.string.FloodWait))
                || text.equals(LocaleController.getString(R.string.PhoneNumberFlood))) {
            return true;
        }
        final String timed = LocaleController.formatString(R.string.FloodWaitTime, "\u0000");
        final int cut = timed.indexOf('\u0000');
        final String prefix = cut > 0 ? timed.substring(0, cut) : "";
        return prefix.length() >= 6 && text.startsWith(prefix);
    }

    private static void showPause(BaseFragment fragment, Activity activity, String flood) {
        int seconds = 0;
        if (flood.startsWith("FLOOD_WAIT_") || flood.startsWith("FLOOD_PREMIUM_WAIT_")) {
            try {
                seconds = Integer.parseInt(flood.substring(flood.lastIndexOf('_') + 1));
            } catch (Throwable ignored) {
            }
        }
        final int lead = flood.equals("PHONE_NUMBER_FLOOD") ? R.string.jac_login_pause_codes
                : flood.equals("PHONE_PASSWORD_FLOOD") ? R.string.jac_login_pause_password
                : R.string.jac_login_pause_attempts;

        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView clock = new TextView(activity);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34);
        clock.setTypeface(AndroidUtilities.bold());
        clock.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        clock.setGravity(Gravity.CENTER);
        final TextView until = new TextView(activity);
        until.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        until.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        until.setGravity(Gravity.CENTER);
        if (seconds > 0) {
            box.addView(clock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));
            box.addView(until, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 8));
        }

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTopImage(LinkGuardUi.glyph(JacIcons.Glyph.WARNING), LinkGuardUi.bandColor())
                .setTitle(JacStrings.get(activity, R.string.jac_login_pause_title))
                .setMessage(JacStrings.get(activity, lead) + "\n\n" + JacStrings.get(activity, R.string.jac_login_pause_scam))
                .setView(seconds > 0 ? box : null)
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .create();
        if (seconds > 0) {
            final long endsAt = SystemClock.elapsedRealtime() + seconds * 1000L;
            final Date wallEnd = new Date(System.currentTimeMillis() + seconds * 1000L);
            until.setText(JacStrings.get(activity, R.string.jac_login_pause_until, seconds >= 20 * 3600
                    ? LocaleController.formatDateChat(wallEnd.getTime() / 1000) + " "
                    + LocaleController.getInstance().getFormatterDay().format(wallEnd)
                    : LocaleController.getInstance().getFormatterDay().format(wallEnd)));
            final Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                if (!dialog.isShowing()) {
                    return;
                }
                final long left = (endsAt - SystemClock.elapsedRealtime() + 999) / 1000;
                if (left <= 0) {
                    clock.setText(JacStrings.get(activity, R.string.jac_login_pause_done));
                    clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
                    clock.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                    until.setVisibility(android.view.View.GONE);
                    return;
                }
                clock.setText(clockText(left));
                AndroidUtilities.runOnUIThread(tick[0], 1000);
            };
            // The first value now; the ticks from when the dialog is up (a tick
            // while it is not showing means it was closed, and stops).
            clock.setText(clockText(seconds));
            AndroidUtilities.runOnUIThread(tick[0], 1000);
        }
        fragment.showDialog(dialog);
    }

    /** 05:07, 1:05:07, or "2 d 01:05:07" — digits read the same in every language. */
    static String clockText(long seconds) {
        final long days = seconds / 86400;
        final long hours = seconds / 3600 % 24;
        final long minutes = seconds / 60 % 60;
        final long secs = seconds % 60;
        final String hms = hours > 0 || days > 0
                ? String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.US, "%02d:%02d", minutes, secs);
        return days > 0 ? LocaleController.formatPluralString("Days", (int) days) + " " + hms : hms;
    }

    private static boolean isAlive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

}
