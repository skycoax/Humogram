package uz.jac.secure.android;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TypefaceSpan;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Two warnings inside upstream's web view — the in-app browser and bots' mini
 * apps share it ({@code BotWebViewContainer.MyWebView}).
 *
 * <h3>A page that asks for what signs you in to Telegram</h3>
 *
 * The way Telegram accounts are stolen in Uzbekistan in 2026: a "vote for my
 * niece", a "Premium gift", an "account check" — a page that looks like
 * Telegram and asks for the phone number, the login code, the cloud password,
 * or for a QR code to be scanned under Settings → Devices. Telegram itself
 * never asks for any of those anywhere but in its own app. So when a page that
 * is not Telegram's shows such a field and talks about Telegram, the page is
 * stopped behind two confirmations, the way a red link is, and "close" closes
 * it.
 *
 * <p>The check is a script run in the page by the web view itself
 * ({@link WebView#evaluateJavascript}) after it loads, again a few seconds
 * later for pages that draw their form late, and when the address changes
 * without a load. It reads the page's form fields and a slice of its text and
 * answers four booleans; nothing leaves the phone, and no bridge into the app
 * is added for the page to call.
 *
 * <h3>A mini app on a dangerous site</h3>
 *
 * Before a bot's mini app loads, its address goes through the link guard's
 * lists ({@link LinkGuard#tierOf}). One on the block list, or a look-alike of
 * a brand, asks twice; "back" closes the mini app. An unknown site does not
 * ask: nearly every mini app lives on a site no list knows, and a question in
 * front of each of them would only teach people to tap through it.
 *
 * <p>Both follow the link protection switch. UI thread; never throws.
 */
public final class WebGuard {

    /** Where Telegram's own sign-in lives; nothing on these is asked about. */
    private static final String[] TELEGRAM_HOSTS = {"telegram.org", "t.me", "telegram.me", "telegram.dog"};

    /** When to look again after a load: forms drawn by script appear late. */
    private static final long[] CHECK_DELAYS_MS = {0, 1500, 4000, 9000};

    /**
     * The page check. Returns {phone, secret, telegram, qr}: a visible field
     * for a phone number; one for a code or a password; the page or its
     * address speaking of signing in to Telegram; a QR code beside words
     * about linking a device. Kept to what a sign-in phishing page cannot do
     * without, so that a shop that only mentions its Telegram channel next to
     * a delivery-phone field is left alone.
     */
    private static final String DETECT_JS = "(function(){try{"
            + "function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);"
            + "return r.width>2&&r.height>2&&s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';}"
            + "var ins=[].slice.call(document.querySelectorAll('input'));var phone=false,secret=false,cells=0;"
            + "for(var i=0;i<ins.length;i++){var e=ins[i];if(!vis(e))continue;var t=(e.type||'').toLowerCase();"
            + "if(/^(hidden|checkbox|radio|submit|button|image|reset|file|range|color)$/.test(t))continue;"
            + "var a=((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')+' '"
            + "+(e.getAttribute('autocomplete')||'')).toLowerCase();"
            + "if(t==='tel'||/(^|[^a-z])(tel|msisdn|mobile)([^a-z]|$)|phone|\\u0442\\u0435\\u043b\\u0435\\u0444\\u043e\\u043d|telefon|raqam/.test(a)"
            + "||/^\\+?\\s?\\d[\\d\\s()_-]{6,}$/.test(e.placeholder||''))phone=true;"
            + "if(t==='password'||/one-time-code|(^|[^a-z])(code|otp|pin|sms|kod|kodni)([^a-z]|$)|\\u043a\\u043e\\u0434|parol|\\u043f\\u0430\\u0440\\u043e\\u043b|password/.test(a))secret=true;"
            + "var ml=parseInt(e.getAttribute('maxlength'),10);if(ml===1)cells++;"
            + "if((e.inputMode==='numeric'||t==='number')&&ml>=4&&ml<=8)secret=true;}"
            + "if(cells>=4)secret=true;"
            + "var qrEl=[].slice.call(document.querySelectorAll('img,canvas,svg')).some(function(m){"
            + "return vis(m)&&/qr/i.test((m.getAttribute('src')||'')+' '+(m.getAttribute('alt')||'')+' '+(m.id||'')+' '+(m.getAttribute('class')||''));});"
            + "if(!phone&&!secret&&!qrEl)return {phone:false,secret:false,telegram:false,qr:false};"
            + "var host=(location.hostname||'').toLowerCase();"
            + "var text=((document.title||'')+' '+((document.body&&document.body.innerText)||'').slice(0,8000)).toLowerCase();"
            // Words, not runs of non-spaces, so that «…kiriting. Telegram kanalimiz»
            // (type your number. Our Telegram channel) is two sentences, not one.
            + "var R='[a-z\\u0430-\\u044f]*';"
            + "var T='(telegram|\\u0442\\u0435\\u043b\\u0435\\u0433\\u0440\\u0430\\u043c'+R+')';"
            + "var LOGIN='(log ?in|sign ?in|\\u0432\\u043e\\u0439\\u0442\\u0438|\\u0432\\u043e\\u0439\\u0434'+R+'|\\u0432\\u0445\\u043e\\u0434'+R"
            + "+'|kir(?:ish|ing|asiz)\\\\b|\\u0430\\u0432\\u0442\\u043e\\u0440\\u0438\\u0437'+R+')';"
            + "var tg=/telegram/.test(host)"
            + "||new RegExp(T+'\\\\s*(code|kodi?|kodni|\\u043a\\u043e\\u0434'+R+'|'+LOGIN+'|akkaunt'+R+'|\\u0430\\u043a\\u043a\\u0430\\u0443\\u043d\\u0442'+R"
            + "+'|account|verif'+R+'|\\u0432\\u0435\\u0440\\u0438\\u0444'+R+'|\\u043f\\u043e\\u0434\\u0442\\u0432\\u0435\\u0440\\u0436'+R+'|tasdiq'+R+')').test(text)"
            + "||new RegExp('(code|kod'+R+'|\\u043a\\u043e\\u0434'+R+')\\\\s*(from|\\u0438\\u0437|in|\\u0432)?\\\\s*'+T).test(text)"
            + "||new RegExp(LOGIN+'\\\\s*(with|via|to|into|\\u0447\\u0435\\u0440\\u0435\\u0437|orqali|\\u0432)?\\\\s*'+T).test(text)"
            + "||new RegExp(T+'\\\\s*(orqali|\\u0447\\u0435\\u0440\\u0435\\u0437)\\\\s*('+LOGIN+'|ovoz'+R+'|\\u0433\\u043e\\u043b\\u043e\\u0441'+R"
            + "+'|tasdiq'+R+'|\\u043f\\u043e\\u0434\\u0442\\u0432\\u0435\\u0440\\u0434'+R+')').test(text)"
            + "||new RegExp(T+'\\\\s*(premium|stars)[\\\\s\\\\S]{0,60}(sovg|gift|\\u043f\\u043e\\u0434\\u0430\\u0440|free|bepul|\\u0431\\u0435\\u0441\\u043f\\u043b\\u0430\\u0442)').test(text)"
            // The cloud password is Telegram's word for its second lock; no other site needs it.
            + "||/cloud password|\\u043e\\u0431\\u043b\\u0430\\u0447\\u043d[\\u0430-\\u044f]* \\u043f\\u0430\\u0440\\u043e\\u043b|bulutli parol|two-step verification"
            + "|\\u0434\\u0432\\u0443\\u0445\\u044d\\u0442\\u0430\\u043f\\u043d|ikki bosqichli/.test(text);"
            + "var qr=qrEl&&/telegram|\\u0442\\u0435\\u043b\\u0435\\u0433\\u0440\\u0430\\u043c/.test(text)"
            + "&&/qurilma|\\u0443\\u0441\\u0442\\u0440\\u043e\\u0439\\u0441\\u0442\\u0432|devices|link desktop|scan|\\u0441\\u043a\\u0430\\u043d\\u0438\\u0440|skaner/.test(text);"
            + "return {phone:phone,secret:secret,telegram:tg,qr:qr};"
            + "}catch(x){return null;}})()";

    /** What one web view has been asked about. UI thread only. */
    private static final class State {
        /** The host the page check last asked about, so one page asks once. */
        String askedHost;
        /** The host the user chose to go on with in spite of it. */
        String approvedHost;
        /** Bumped on every load, so late checks of a page gone by drop out. */
        int load;
    }

    private static final Map<WebView, State> states = new WeakHashMap<>();
    /** Mini app sites the user confirmed in this process. UI thread only. */
    private static final Set<String> approvedMiniApps = new HashSet<>();

    private WebGuard() {
    }

    private static boolean isOn() {
        try {
            final Context context = ApplicationLoader.applicationContext;
            return context != null && HumogramConfig.isLinkGuard(context);
        } catch (Throwable t) {
            return true;
        }
    }

    /** The host of a web address, lower case, or "" when it has none. */
    private static String hostOf(String url) {
        try {
            final String host = url != null ? Uri.parse(url).getHost() : null;
            return host != null ? host.toLowerCase(Locale.ROOT) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isTelegramHost(String host) {
        for (String own : TELEGRAM_HOSTS) {
            if (host.equals(own) || host.endsWith("." + own)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // The page check
    // ------------------------------------------------------------------

    /**
     * From {@code MyWebView}'s {@code onPageFinished} and
     * {@code doUpdateVisitedHistory}: look at the page now and a few times
     * over the next seconds.
     *
     * @param close what closes the page — the browser tab or the mini app
     */
    public static void onPage(WebView webView, String url, Runnable close) {
        try {
            if (webView == null || close == null || !isOn()) {
                return;
            }
            final String host = hostOf(url);
            if (host.isEmpty() || isTelegramHost(host)
                    || !(url.startsWith("http://") || url.startsWith("https://"))) {
                return;
            }
            State state = states.get(webView);
            if (state == null) {
                state = new State();
                states.put(webView, state);
            }
            if (host.equals(state.askedHost) || host.equals(state.approvedHost)) {
                return;
            }
            final int load = ++state.load;
            final State watched = state;
            for (long delay : CHECK_DELAYS_MS) {
                AndroidUtilities.runOnUIThread(() -> check(webView, host, load, watched, close), delay);
            }
        } catch (Throwable t) {
            FileLog.e("web guard: page check failed: " + t.getClass().getSimpleName());
        }
    }

    private static void check(WebView webView, String host, int load, State state, Runnable close) {
        try {
            if (state.load != load || host.equals(state.askedHost) || !webView.isAttachedToWindow()
                    || !host.equals(hostOf(webView.getUrl()))) {
                return;
            }
            webView.evaluateJavascript(DETECT_JS, value -> {
                try {
                    if (value == null || value.equals("null") || state.load != load || host.equals(state.askedHost)) {
                        return;
                    }
                    final JSONObject found = new JSONObject(value);
                    final boolean signIn = found.optBoolean("telegram")
                            && (found.optBoolean("phone") || found.optBoolean("secret"));
                    if (signIn || found.optBoolean("qr")) {
                        state.askedHost = host;
                        askAboutPage(webView, host, state, close);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable t) {
            FileLog.e("web guard: script failed: " + t.getClass().getSimpleName());
        }
    }

    /** Step 1 of 2 — why. "Close the page" is the button under the thumb. */
    private static void askAboutPage(WebView webView, String host, State state, Runnable close) {
        final Activity activity = AndroidUtilities.findActivity(webView.getContext());
        if (!isAlive(activity)) {
            return;
        }
        final boolean[] going = {false};
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTopImage(LinkGuardUi.glyph(JacIcons.Glyph.BLOCK), LinkGuardUi.bandColor())
                .setTitle(JacStrings.get(activity, R.string.jac_webguard_login_title))
                .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 1, 2))
                .setMessage(bolded(JacStrings.get(activity, R.string.jac_webguard_login_body, host), host))
                .setView(hostView(activity, host))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_webguard_close), (d, which) -> closePage(webView, close))
                .setNegativeButton(JacStrings.get(activity, R.string.jac_link_continue), (d, which) -> {
                    going[0] = true;
                    confirmPage(activity, webView, host, state, close);
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);
        // Back is "close" too; the negative button cancels the dialog after
        // its own click as well, hence the flag (see ChatGuardUi).
        dialog.setOnCancelListener(d -> {
            if (!going[0]) {
                closePage(webView, close);
            }
        });
        dialog.show();
        LinkGuardUi.colourButton(dialog, DialogInterface.BUTTON_NEGATIVE, Theme.getColor(Theme.key_text_RedBold));
        JacAlarm.sound(activity);
    }

    /** Step 2 of 2 — the checkbox and the hold; anything but the red button closes the page. */
    private static void confirmPage(Activity activity, WebView webView, String host, State state, Runnable close) {
        try {
            if (!isAlive(activity)) {
                return;
            }
            final CheckBoxCell acknowledge = checkbox(activity, R.string.jac_webguard_confirm_checkbox);
            final boolean[] done = {false};
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTopImage(LinkGuardUi.glyph(JacIcons.Glyph.WARNING), LinkGuardUi.bandColor())
                    .setTitle(JacStrings.get(activity, R.string.jac_webguard_confirm_title))
                    .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 2, 2))
                    .setMessage(JacStrings.get(activity, R.string.jac_webguard_confirm_body))
                    .setView(acknowledge)
                    .setPositiveButton(JacStrings.get(activity, R.string.jac_webguard_proceed), (d, which) -> {
                        done[0] = true;
                        state.approvedHost = host;
                    })
                    .setNegativeButton(JacStrings.get(activity, R.string.jac_quarantine_confirm_cancel), null)
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(d -> {
                if (!done[0]) {
                    closePage(webView, close);
                }
            });
            dialog.show();
            holdUntilAcknowledged(dialog, acknowledge);
        } catch (Throwable t) {
            FileLog.e("web guard: step two failed: " + t.getClass().getSimpleName());
            closePage(webView, close);
        }
    }

    private static void closePage(WebView webView, Runnable close) {
        try {
            webView.stopLoading();
        } catch (Throwable ignored) {
        }
        try {
            close.run();
        } catch (Throwable t) {
            FileLog.e("web guard: close failed: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // Mini apps
    // ------------------------------------------------------------------

    /**
     * From {@code BotWebViewContainer.loadUrl}, before a bot's mini app
     * loads. True when the guard took over: it asks twice and runs
     * {@code proceed} — the same load again — only after both, and
     * {@code close} on every other way out. False when the caller should load
     * as before.
     */
    public static boolean holdMiniApp(View anchor, String url, Runnable proceed, Runnable close) {
        try {
            if (anchor == null || url == null || proceed == null || close == null || !isOn()) {
                return false;
            }
            final String host = hostOf(url);
            if (host.isEmpty() || approvedMiniApps.contains(host)
                    || LinkGuard.tierOf(url) != LinkGuard.TIER_DANGEROUS) {
                return false;
            }
            final Activity activity = AndroidUtilities.findActivity(anchor.getContext());
            if (!isAlive(activity)) {
                return false;
            }
            final boolean[] going = {false};
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTopImage(LinkGuardUi.glyph(JacIcons.Glyph.BLOCK), LinkGuardUi.bandColor())
                    .setTitle(JacStrings.get(activity, R.string.jac_webguard_miniapp_title))
                    .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 1, 2))
                    .setMessage(bolded(JacStrings.get(activity, R.string.jac_webguard_miniapp_body, host), host))
                    .setView(hostView(activity, host))
                    .setPositiveButton(JacStrings.get(activity, R.string.jac_link_back), (d, which) -> close.run())
                    .setNegativeButton(JacStrings.get(activity, R.string.jac_link_continue), (d, which) -> {
                        going[0] = true;
                        confirmMiniApp(activity, host, proceed, close);
                    })
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnCancelListener(d -> {
                if (!going[0]) {
                    close.run();
                }
            });
            dialog.show();
            LinkGuardUi.colourButton(dialog, DialogInterface.BUTTON_NEGATIVE, Theme.getColor(Theme.key_text_RedBold));
            JacAlarm.voice(activity, R.raw.jac_voice_link);
            return true;
        } catch (Throwable t) {
            FileLog.e("web guard: mini app check failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static void confirmMiniApp(Activity activity, String host, Runnable proceed, Runnable close) {
        try {
            if (!isAlive(activity)) {
                return;
            }
            final CheckBoxCell acknowledge = checkbox(activity, R.string.jac_linkguard_confirm_checkbox);
            final boolean[] done = {false};
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTopImage(LinkGuardUi.glyph(JacIcons.Glyph.WARNING), LinkGuardUi.bandColor())
                    .setTitle(JacStrings.get(activity, R.string.jac_linkguard_confirm_title))
                    .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 2, 2))
                    .setMessage(bolded(JacStrings.get(activity, R.string.jac_linkguard_confirm_dangerous, host), host))
                    .setView(acknowledge)
                    .setPositiveButton(JacStrings.get(activity, R.string.jac_chatguard_open), (d, which) -> {
                        done[0] = true;
                        approvedMiniApps.add(host);
                        proceed.run();
                    })
                    .setNegativeButton(JacStrings.get(activity, R.string.jac_quarantine_confirm_cancel), null)
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(d -> {
                if (!done[0]) {
                    close.run();
                }
            });
            dialog.show();
            holdUntilAcknowledged(dialog, acknowledge);
        } catch (Throwable t) {
            FileLog.e("web guard: mini app step two failed: " + t.getClass().getSimpleName());
            close.run();
        }
    }

    // ------------------------------------------------------------------
    // The parts both warnings are made of
    // ------------------------------------------------------------------

    /** Upstream's dialog checkbox, set up the way the other guards set it up. */
    private static CheckBoxCell checkbox(Activity activity, int textRes) {
        final CheckBoxCell acknowledge = new CheckBoxCell(activity, 1);
        acknowledge.setMultiline(true);
        acknowledge.getTextView().getLayoutParams().width = LayoutHelper.MATCH_PARENT;
        acknowledge.getTextView().setSingleLine(false);
        acknowledge.getTextView().setMaxLines(3);
        acknowledge.getTextView().setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        acknowledge.setText(JacStrings.get(activity, textRes), "", false, false);
        return acknowledge;
    }

    /** The red button stays inert until the box is ticked and the three seconds are over. */
    private static void holdUntilAcknowledged(AlertDialog dialog, CheckBoxCell acknowledge) {
        final View go = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        LinkGuardUi.colourButton(dialog, DialogInterface.BUTTON_POSITIVE, Theme.getColor(Theme.key_text_RedBold));
        final boolean[] held = {true};
        LinkGuardUi.setEnabled(go, false);
        acknowledge.setOnClickListener(v -> {
            acknowledge.setChecked(!acknowledge.isChecked(), true);
            LinkGuardUi.setEnabled(go, acknowledge.isChecked() && !held[0]);
        });
        LinkGuardUi.countDown(dialog, () -> {
            held[0] = false;
            LinkGuardUi.setEnabled(go, acknowledge.isChecked());
        });
    }

    private static CharSequence bolded(String text, String part) {
        final SpannableStringBuilder out = new SpannableStringBuilder(text);
        final int at = text.indexOf(part);
        if (at >= 0) {
            out.setSpan(new TypefaceSpan(AndroidUtilities.bold()), at, at + part.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** The site in the same pill the link and chat warnings show theirs in. */
    private static View hostView(Context context, String host) {
        final LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setPadding(dp(14), dp(12), dp(14), dp(12));
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(22));
        background.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack), 0.06f));
        pill.setBackground(background);

        final TextView title = new TextView(context);
        title.setText(host);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pill.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(pill, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 4, 22, 9));
        return container;
    }

    private static boolean isAlive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }
}
