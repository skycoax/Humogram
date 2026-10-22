package uz.jac.secure.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.R;

import uz.jac.secure.core.model.Verdict;

/**
 * The full-screen warning shown before a flagged link opens.
 *
 * <h3>Why a screen and not a dialog</h3>
 *
 * This replaced a system {@code AlertDialog}, and the reasons are about what a
 * dialog trains people to do rather than about looks.
 *
 * <ul>
 *   <li>A dialog is the same object that asks about notifications and app
 *       ratings. It is dismissed dozens of times a week, usually without being
 *       read, and by the same thumb movement each time. Putting the one warning
 *       that matters into that stream guarantees it inherits the habit.</li>
 *   <li>A dialog has no room. The single most useful thing we can show is the
 *       real destination in full — scheme, host, path, and the punycode
 *       underneath it — and an AlertDialog message truncates or scrolls it.</li>
 *   <li>A dialog is dismissible by tapping outside it, and "dismiss" has no
 *       defined meaning here. Going back is a decision; drifting past is
 *       not.</li>
 *   <li>Its buttons are two equal words in a row, and the platform puts the
 *       affirmative on the right, where the thumb rests. We need the safe
 *       action to be the large, obvious, default one.</li>
 * </ul>
 *
 * So: the shield, one sentence, the real destination, and <b>Go back</b> as a
 * full-width primary button. Continuing is a quiet text link below it, and it
 * costs a second confirmation.
 *
 * <h3>What it shows about the address</h3>
 *
 * The URL is rendered in its <em>Unicode</em> form, with the host highlighted —
 * that is the form the attacker built and the form the user would have seen. If
 * the ASCII form differs, it is printed underneath in monospace, quieter, as
 * the ground truth. Showing only punycode would be technically honest and
 * practically useless: {@code xn--lik-3edc.uz} tells a non-technical reader
 * nothing, while {@code сlick.uz} next to it is the thing that makes the trick
 * visible.
 *
 * <h3>Result contract</h3>
 *
 * {@code RESULT_OK} means the user passed both confirmations and the caller
 * should open the link. Anything else — back button, back gesture, the primary
 * button — means do not open it. There is no path through this Activity that
 * opens a link itself.
 */
public final class LinkInterstitialActivity extends Activity {

    public static final String EXTRA_URL = "jac.url";
    public static final String EXTRA_DISPLAY_HOST = "jac.display_host";
    public static final String EXTRA_VERDICT = "jac.verdict";
    public static final String EXTRA_REASON = "jac.reason";
    public static final String EXTRA_DISPLAY_TEXT = "jac.display_text";
    public static final String EXTRA_CHAIN = "jac.chain";

    /**
     * Build the launch Intent from a scanned link.
     *
     * Takes the already-localised reason rather than a signal code, because the
     * caller has the {@code LinkVerdict} and this Activity would otherwise have
     * to re-derive it from an Intent extra.
     */
    public static Intent intentFor(
            Context context,
            String canonicalUrl,
            String displayHost,
            Verdict verdict,
            String localisedReason,
            String displayText,
            String[] redirectChain
    ) {
        Intent intent = new Intent(context, LinkInterstitialActivity.class);
        intent.putExtra(EXTRA_URL, canonicalUrl);
        intent.putExtra(EXTRA_DISPLAY_HOST, displayHost);
        intent.putExtra(EXTRA_VERDICT, verdict != null ? verdict.name() : Verdict.SUSPICIOUS.name());
        intent.putExtra(EXTRA_REASON, localisedReason);
        intent.putExtra(EXTRA_DISPLAY_TEXT, displayText);
        intent.putExtra(EXTRA_CHAIN, redirectChain);
        return intent;
    }

    private boolean malicious;
    private int accent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = stringExtra(EXTRA_URL, "");
        String displayHost = stringExtra(EXTRA_DISPLAY_HOST, "");
        String reason = stringExtra(EXTRA_REASON, null);
        String displayText = stringExtra(EXTRA_DISPLAY_TEXT, null);
        String[] chain = getIntent().getStringArrayExtra(EXTRA_CHAIN);

        malicious = Verdict.MALICIOUS.name().equals(stringExtra(EXTRA_VERDICT, ""));
        // Red for a suspicious link too: a threat is never shown in yellow.
        accent = JacTheme.danger(this);

        setResult(RESULT_CANCELED);
        setContentView(buildContent(url, displayHost, reason, displayText, chain));
    }

    private View buildContent(String url, String displayHost, String reason, String displayText, String[] chain) {
        int pad = JacTheme.dp(this, 24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(JacTheme.screen(this));
        root.setPadding(pad, pad, pad, JacTheme.dp(this, 20));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroller.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(buildShield(), marginParams(JacTheme.dp(this, 72), JacTheme.dp(this, 72), JacTheme.dp(this, 40)));

        TextView title = new TextView(this);
        title.setText(malicious
                ? R.string.jac_link_dangerous_title
                : R.string.jac_link_suspicious_title);
        title.setTextSize(22f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(JacTheme.text(this));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(title, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, JacTheme.dp(this, 24)));

        if (!TextUtils.isEmpty(reason)) {
            content.addView(paragraph(reason, JacTheme.textMuted(this), Gravity.CENTER_HORIZONTAL),
                    marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, JacTheme.dp(this, 12)));
        }

        // The display-text mismatch is called out separately from the reason.
        // "The message showed click.uz" is the fact the user can verify by
        // scrolling back, and it is what makes the rest of the screen credible.
        if (!TextUtils.isEmpty(displayText) && !displayText.equals(displayHost)) {
            content.addView(
                    paragraph(getString(R.string.jac_link_display_mismatch, displayText),
                            JacTheme.textMuted(this), Gravity.CENTER_HORIZONTAL),
                    marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, JacTheme.dp(this, 8)));
        }

        TextView label = new TextView(this);
        label.setText(R.string.jac_link_real_destination);
        label.setTextSize(11f);
        label.setLetterSpacing(0.12f);
        label.setAllCaps(true);
        label.setTextColor(JacTheme.textMuted(this));
        content.addView(label, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, JacTheme.dp(this, 32)));

        content.addView(buildDestinationCard(url, displayHost),
                marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, JacTheme.dp(this, 10)));

        if (chain != null && chain.length > 0) {
            content.addView(
                    paragraph(getString(R.string.jac_link_redirect_chain, TextUtils.join(" → ", chain)),
                            JacTheme.textMuted(this), Gravity.START),
                    marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, JacTheme.dp(this, 12)));
        }

        LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroller, scrollerParams);

        root.addView(buildBackButton(), marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                JacTheme.dp(this, 52), JacTheme.dp(this, 16)));
        root.addView(buildContinueLink(), marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, JacTheme.dp(this, 6)));

        return root;
    }

    /** The shield, in a tinted disc — the app's own mark, used as the alarm. */
    private View buildShield() {
        return new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF bounds = new RectF();

            @Override
            protected void onDraw(Canvas canvas) {
                float radius = Math.min(getWidth(), getHeight()) / 2f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(JacTheme.wash(accent, 16));
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, paint);

                float glyph = radius * 1.05f;
                bounds.set(getWidth() / 2f - glyph / 2f, getHeight() / 2f - glyph / 2f,
                        getWidth() / 2f + glyph / 2f, getHeight() / 2f + glyph / 2f);
                paint.setColor(accent);
                JacIcons.draw(canvas, JacIcons.Glyph.SHIELD, bounds, paint);
            }
        };
    }

    /**
     * The address, twice: readable form on top, ASCII truth underneath.
     *
     * Only rendered twice when the two actually differ. On a plain ASCII
     * phishing domain a second identical line would read as though there were
     * something more to notice, and there is not.
     */
    private View buildDestinationCard(String url, String displayHost) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = JacTheme.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable background = new GradientDrawable();
        background.setColor(JacTheme.card(this));
        background.setCornerRadius(JacTheme.dp(this, 12));
        card.setBackground(background);

        String asciiHost = hostOf(url);
        String unicodeUrl = TextUtils.isEmpty(displayHost) || TextUtils.isEmpty(asciiHost)
                ? url
                : url.replaceFirst(java.util.regex.Pattern.quote(asciiHost), java.util.regex.Matcher.quoteReplacement(displayHost));

        TextView primary = new TextView(this);
        primary.setTextSize(15f);
        primary.setTypeface(android.graphics.Typeface.MONOSPACE);
        primary.setTextColor(JacTheme.textMuted(this));
        primary.setText(highlightHost(unicodeUrl, TextUtils.isEmpty(displayHost) ? asciiHost : displayHost));
        card.addView(primary);

        if (!TextUtils.isEmpty(asciiHost) && !asciiHost.equals(displayHost)) {
            TextView punycode = new TextView(this);
            punycode.setTextSize(12f);
            punycode.setTypeface(android.graphics.Typeface.MONOSPACE);
            punycode.setTextColor(JacTheme.textMuted(this));
            punycode.setText(asciiHost);
            card.addView(punycode, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, JacTheme.dp(this, 4)));
        }

        return card;
    }

    /**
     * Colour the host and leave the rest grey.
     *
     * The host is the only part of a URL that decides where you end up, and a
     * long path is what phishing uses to push it off the visible line. Making
     * it the one coloured, bold run is the difference between showing the
     * address and showing it usefully.
     */
    private CharSequence highlightHost(String url, String host) {
        SpannableStringBuilder builder = new SpannableStringBuilder(url);
        if (TextUtils.isEmpty(host)) {
            return builder;
        }
        int start = url.indexOf(host);
        if (start < 0) {
            return builder;
        }
        int end = start + host.length();
        builder.setSpan(new ForegroundColorSpan(accent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    private View buildBackButton() {
        TextView button = new TextView(this);
        button.setText(R.string.jac_link_back);
        button.setTextSize(16f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setTextColor(JacTheme.onPrimary(this));
        button.setGravity(Gravity.CENTER);

        GradientDrawable background = new GradientDrawable();
        background.setColor(JacTheme.primary(this));
        background.setCornerRadius(JacTheme.dp(this, 12));
        button.setBackground(background);

        button.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        return button;
    }

    /**
     * Continuing is text, not a button, and it opens a second confirmation.
     *
     * The asymmetry is the design. One tap is something a thumb does; the
     * second dialog names what happens and is worded differently, so the same
     * reflex does not clear both.
     */
    private View buildContinueLink() {
        TextView link = new TextView(this);
        link.setText(R.string.jac_link_continue);
        link.setTextSize(15f);
        link.setTextColor(JacTheme.textMuted(this));
        link.setGravity(Gravity.CENTER);
        link.setPadding(0, JacTheme.dp(this, 14), 0, JacTheme.dp(this, 14));
        link.setOnClickListener(v -> confirmContinue());
        return link;
    }

    private void confirmContinue() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.jac_link_continue_confirm)
                .setMessage(stringExtra(EXTRA_URL, ""))
                .setNegativeButton(R.string.jac_quarantine_confirm_cancel, null)
                .setPositiveButton(R.string.jac_link_continue_confirm_action, (dialog, which) -> {
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_URL, stringExtra(EXTRA_URL, "")));
                    finish();
                })
                .show();
    }

    private TextView paragraph(String text, int colour, int gravity) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15f);
        view.setLineSpacing(JacTheme.dp(this, 4), 1f);
        view.setTextColor(colour);
        view.setGravity(gravity);
        return view;
    }

    private LinearLayout.LayoutParams marginParams(int width, int height, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = topMargin;
        return params;
    }

    private String stringExtra(String key, String fallback) {
        String value = getIntent() != null ? getIntent().getStringExtra(key) : null;
        return value != null ? value : fallback;
    }

    /**
     * Host of a canonical URL, by string scan.
     *
     * Not {@code Uri.parse}: this string is attacker-controlled and the whole
     * screen exists because its host is deceptive. Android's parser is
     * permissive and its idea of the authority has, historically, differed from
     * the one the request actually uses. The scanner already canonicalised this
     * URL, so the form is known and a substring is both sufficient and
     * incapable of disagreeing with the value we scanned.
     */
    private static String hostOf(String canonicalUrl) {
        if (canonicalUrl == null) {
            return "";
        }
        int schemeEnd = canonicalUrl.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }
        int start = schemeEnd + 3;
        int end = canonicalUrl.length();
        for (int i = start; i < canonicalUrl.length(); i++) {
            char c = canonicalUrl.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i;
                break;
            }
        }
        return canonicalUrl.substring(start, end);
    }

    @Override
    public void onBackPressed() {
        // Back is the safe action here, so it needs no confirmation — but it
        // must not fall through to a default that could be read as consent.
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(JacTheme.screen(this));
        }
    }
}
