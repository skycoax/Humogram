package uz.jac.secure.android;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.TypefaceSpan;
import org.telegram.ui.Components.URLSpanBrowser;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Components.URLSpanReplacement;
import org.telegram.ui.LaunchActivity;

import java.lang.ref.WeakReference;
import java.net.IDN;
import java.util.List;
import java.util.Locale;

import uz.jac.secure.core.link.LinkDecision;
import uz.jac.secure.core.link.LinkReason;
import uz.jac.secure.core.link.LinkTier;

/**
 * Everything the user sees of the link guard: the red on a link whose site is
 * not on the trusted list, and the two confirmations that stand between a tap
 * on such a link and the page opening.
 *
 * <p>{@link LinkGuard} decides; this class only shows. It is called from a
 * handful of one-line patches in upstream ({@code URLSpanBrowser} and its
 * siblings for the colour, {@code Browser} for the gate), so every method here
 * is written to be safe from a draw pass and from an arbitrary thread, and to
 * leave upstream exactly as it was when the guard has nothing to say.
 *
 * <h3>Failing</h3>
 *
 * Two different directions, on purpose. Colouring fails <em>open</em>: any
 * trouble in {@link #tint} leaves the link the colour upstream gave it, because
 * a crash while drawing a message takes the whole chat with it. Opening fails
 * <em>closed</em>: if the engine throws, the link is treated as unknown and
 * asked about; if there is no Activity to ask in, it is not opened at all. Not
 * opening a link is recoverable — the user taps again. Opening a phishing page
 * is not.
 *
 * <h3>Built from Telegram's own dialog</h3>
 *
 * Both confirmations are upstream {@link AlertDialog}s with upstream's
 * {@link CheckBoxCell}, the same parts {@link QuarantineDialogs} is built from
 * and for the same reason: two steps that do not look alike, because a second
 * identical "are you sure?" is dismissed by the same reflex as the first.
 */
public final class LinkGuardUi {

    private LinkGuardUi() {
    }

    // ------------------------------------------------------------------
    // Colour
    // ------------------------------------------------------------------

    /**
     * The WCAG 2.1 contrast a link's ink must reach against what it is drawn
     * on (AA for body text).
     */
    private static final double MIN_CONTRAST = 4.5;

    /**
     * How far the themed red may be pushed towards white (on a dark bubble) or
     * black (on a light one) to become legible, in tenths. Past 40 % it is
     * pink or brown, and "this link is red" is the whole message.
     */
    private static final int MAX_SHADE_STEPS = 4;

    /**
     * The same for a fill, which has more room than ink: it is a patch of
     * colour, not thin strokes, and a deep red patch still reads as red.
     */
    private static final int MAX_FILL_STEPS = 6;

    /**
     * How much of the block's fill the wash under an unknown link is. Enough
     * to turn the bubble visibly red behind the words; little enough that the
     * highlights upstream draws beneath the text still show through it.
     */
    private static final float WASH = 0.35f;

    /** Entries in {@link #inks}; a theme has a handful of bubble colours. */
    private static final int INK_CACHE = 12;

    /**
     * (surface, themed red) → the red to write on that surface, 0 for "none is
     * legible". Flat triples, replaced whole on a miss, so readers on any
     * thread see a complete table without a lock.
     */
    private static volatile int[] inks = new int[0];

    /**
     * (themed red, ink, the fill chosen for them); empty before first use.
     * One triple, replaced whole on a miss, for the same reason.
     */
    private static volatile int[] blockFill = new int[0];

    /**
     * Draw a link red when its site is not on the trusted list.
     *
     * <p>Called from the {@code URLSpan} subclasses' {@code updateDrawState},
     * after {@code super} and the style run. {@code baseColor} is the paint's
     * colour captured <em>before</em> {@code super} — the bubble's own text
     * colour, which together with {@code paint.linkColor} is all a span can
     * know about the surface it is drawn on.
     *
     * <h3>Which red</h3>
     *
     * One red does not work. {@code key_text_RedRegular} (0xCC2929 in the
     * light themes, 0xEE686F in night and dark blue) was measured against
     * every bubble the bundled themes draw:
     *
     * <pre>
     *   classic      in  FFFFFF 5.36   out EFFFDE 5.11   red text
     *   blue bubbles in  FFFFFF 5.36   out E6F2FC 4.72   red text
     *   day          in  F0F0F0 4.70                     red text
     *   arctic       in  FFFFFF 5.36                     red text
     *   night        in  1F2123 5.27                     red text
     *   dark blue    in  232E3B 4.49   one shade lighter (EF777D): 4.99
     *   day          out 2D7ED5 1.29   (gradient stop 5CB6E0: 2.35)
     *   arctic       out 3490EB 1.62
     *   night        out 366CAF 1.75   (gradient stop 3B8CB9: 1.21)
     *   dark blue    out 3E618A 2.09
     * </pre>
     *
     * The last four cannot be fixed by lightening the red. On day's and
     * arctic's saturated blue even pure white only reaches 4.15 and 3.31, so
     * no light colour at all passes 4.5 there; on the two dark themes' blue a
     * red would have to be lightened until it is pink-white (luminance 0.69
     * and up) to pass. Where no red is legible as ink, the red goes under the
     * text instead of into it.
     *
     * <h3>Three looks, two meanings</h3>
     *
     * <b>Unknown site, red ink legible:</b> red text. The rows marked so
     * above.
     *
     * <b>Unknown site, no legible red ink:</b> the bubble's own text colour on
     * a translucent wash of red ({@link #WASH} of the block's fill). The text
     * keeps the colour its bubble chose for it, and the wash is a deep enough
     * red that on three of the four bubbles the words gain contrast, and on
     * the fourth stay well clear of 4.5 (text against what is behind it,
     * before → after):
     *
     * <pre>
     *   day          out 2D7ED5 4.15 → 5.71   (gradient stop 5CB6E0: 2.28 → 3.60)
     *   arctic       out 3490EB 3.31 → 4.88
     *   night        out 366CAF 5.13 → 5.37   (gradient stop 3B8CB9: 3.57 → 4.25)
     *   dark blue    out 3E618A 6.13 → 5.86
     * </pre>
     *
     * (A wash of the themed red itself would be lighter than the bubble in the
     * two dark themes and take night down to 4.64; the fill is the same red
     * already deepened for light ink.)
     *
     * It is also what an unknown link gets on a surface the span cannot
     * identify — a photo caption, a chat with its own theme, a quote block —
     * because it leaves the text the colour its surface chose for it. Being
     * translucent, it lets the pressed-link, search and selection highlights
     * that {@code ChatMessageCell} draws before the text show through.
     *
     * <b>Blocked site:</b> always the solid block — the ink upstream puts on
     * its filled buttons ({@code key_featuredStickers_buttonText}) on the
     * themed red, deepened where that ink would not read on it: white on
     * CC2929 is 5.36 in the four light themes; in night and dark blue the
     * red is EE686F (3.07 under white) and the fill is BE5358, two shades
     * deeper, 4.60. It reads the same on every bubble, and nothing an unknown
     * site is drawn as looks like it.
     *
     * <h3>Spoilers</h3>
     *
     * A hidden spoiler is hidden by making its text transparent
     * ({@code SpoilerEffect}), which leaves a background in place: a block or
     * a wash would show through it as a red bar the width of the link. So a
     * link inside a spoiler never gets a background. It is given red ink where
     * red ink is legible — invisible while hidden like the rest of the text,
     * red once revealed — and is otherwise left as upstream drew it. The run
     * carries no "revealed" mark in a chat (the message does), so this
     * applies before and after the reveal alike.
     *
     * <h3>Still a link</h3>
     *
     * The underline is forced on. Red text without it reads as an error
     * message, not as something to tap; text links ({@code URLSpanReplacement})
     * are not underlined upstream, and a red one has to be.
     *
     * <p>Thread-safe and allocation-free on the hot path: layouts are measured
     * off the UI thread and {@code updateDrawState} runs there too.
     * {@link LinkGuard#tierOf} is already memoised per host and list
     * generation, so nothing is cached on the span.
     */
    public static void tint(URLSpan span, TextPaint paint, int baseColor) {
        try {
            final String url = span.getURL();
            if (url == null || !carriesWebAddress(span, url)) {
                return;
            }
            final int tier = LinkGuard.tierOf(url);
            if (tier != LinkGuard.TIER_UNKNOWN && tier != LinkGuard.TIER_DANGEROUS) {
                return;
            }
            // The text fades with its bubble (ChatMessageCell scales the
            // paint's alpha during transitions); the red has to fade with it.
            final int alpha = Color.alpha(baseColor);
            final int red = Theme.getColor(Theme.key_text_RedRegular) | 0xFF000000;
            final boolean spoiler = insideSpoiler(span);
            final int ink = tier == LinkGuard.TIER_DANGEROUS && !spoiler
                    ? 0 : redTextFor(paint.linkColor | 0xFF000000, baseColor | 0xFF000000, red);
            if (ink != 0) {
                paint.setColor(withAlpha(ink, alpha));
            } else if (spoiler) {
                // No ink to give it, and a background would show through the
                // hidden spoiler. Left exactly as upstream drew it.
                return;
            } else {
                final int onBlock = Theme.getColor(Theme.key_featuredStickers_buttonText) | 0xFF000000;
                final int fill = blockFillFor(red, onBlock);
                if (tier == LinkGuard.TIER_DANGEROUS) {
                    paint.bgColor = withAlpha(fill, alpha);
                    paint.setColor(withAlpha(onBlock, alpha));
                } else {
                    paint.bgColor = ColorUtils.setAlphaComponent(fill, Math.round(WASH * alpha));
                    paint.setColor(baseColor);
                }
            }
            paint.setUnderlineText(true);
        } catch (Throwable ignored) {
            // A draw pass must never die for a colour.
        }
    }

    /**
     * For {@code ChatMessageCell}: the highlight under a pressed link. A link
     * the guard drew red would otherwise flash link-blue under the finger.
     */
    public static void tintPressed(LinkSpanDrawable<?> pressed) {
        try {
            if (pressed == null || !(pressed.getSpan() instanceof URLSpan)) {
                return;
            }
            final URLSpan span = (URLSpan) pressed.getSpan();
            final String url = span.getURL();
            if (url == null || !carriesWebAddress(span, url)) {
                return;
            }
            final int tier = LinkGuard.tierOf(url);
            if (tier == LinkGuard.TIER_UNKNOWN || tier == LinkGuard.TIER_DANGEROUS) {
                // The same strength upstream's own highlight colours carry
                // (0x33 alpha); LinkSpanDrawable scales it further itself.
                pressed.setColor(Theme.multAlpha(Theme.getColor(Theme.key_text_RedRegular), 0.2f));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whether this link sits in a spoiler that has not been marked revealed.
     * The two span classes a message's links are made of carry the style run
     * they were built from; nothing else can be asked.
     */
    private static boolean insideSpoiler(URLSpan span) {
        final TextStyleSpan.TextStyleRun run;
        if (span instanceof URLSpanBrowser) {
            run = ((URLSpanBrowser) span).getStyle();
        } else if (span instanceof URLSpanReplacement) {
            run = ((URLSpanReplacement) span).getTextStyleRun();
        } else {
            return false;
        }
        return run != null && (run.flags & TextStyleSpan.FLAG_STYLE_SPOILER) != 0
                && (run.flags & TextStyleSpan.FLAG_STYLE_SPOILER_REVEALED) == 0;
    }

    /**
     * Whether this span is a link to a site at all.
     *
     * <p>{@code URLSpanNoUnderline} doubles as the span for @mentions, #tags,
     * /commands, video timestamps, card numbers and numeric user ids; in it
     * only a spelled-out web address — http(s), or a TON site, which the core
     * judges like one — is a link. The other two classes always carry an
     * address, and {@link LinkGuard#tierOf} sorts tg:, mailto: and tel: out by
     * itself.
     */
    private static boolean carriesWebAddress(URLSpan span, String url) {
        if (url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8)
                || url.regionMatches(true, 0, "tonsite://", 0, 10)) {
            return true;
        }
        return !(span instanceof URLSpanNoUnderline);
    }

    /**
     * The red to write this link in, or 0 when no red is legible there.
     *
     * <p>The link colour says which bubble this is: {@code ChatMessageCell}
     * sets {@code linkColor} to the incoming or the outgoing theme key before
     * it draws, so a match names the bubble and its colour can be looked up
     * and measured. No match means somewhere else — a bio, a caption, a quote
     * block (whose links take the peer colour) or a chat with its own theme —
     * and there the only evidence is the text colour: dark text means a light
     * surface, for which the window background stands in; light text means a
     * dark or saturated one, where nothing can be proved and the answer is 0.
     */
    private static int redTextFor(int linkColor, int textColor, int red) {
        final boolean incoming = linkColor == (Theme.getColor(Theme.key_chat_messageLinkIn) | 0xFF000000);
        final boolean outgoing = linkColor == (Theme.getColor(Theme.key_chat_messageLinkOut) | 0xFF000000);
        if (!incoming && !outgoing) {
            if (luma(textColor) >= 96) {
                return 0;
            }
            return inkOn(Theme.getColor(Theme.key_windowBackgroundWhite), textColor, red);
        }
        // Themes that use one link colour for both bubbles (the classic one)
        // match both; the red then has to work on both.
        int ink = 0;
        if (incoming) {
            ink = inkOn(Theme.getColor(Theme.key_chat_inBubble), textColor, red);
            if (ink == 0) {
                return 0;
            }
        }
        if (outgoing) {
            final int own = inkOn(Theme.getColor(Theme.key_chat_outBubble), textColor, red);
            if (own == 0 || (incoming && own != ink)) {
                return 0;
            }
            ink = own;
            // An outgoing bubble can be a gradient; the text may sit anywhere
            // on it, so every stop has to take the same ink.
            if (!sameInkOn(Theme.key_chat_outBubbleGradient1, textColor, red, ink)
                    || !sameInkOn(Theme.key_chat_outBubbleGradient2, textColor, red, ink)
                    || !sameInkOn(Theme.key_chat_outBubbleGradient3, textColor, red, ink)) {
                return 0;
            }
        }
        return ink;
    }

    private static boolean sameInkOn(int gradientKey, int textColor, int red, int ink) {
        final int stop = Theme.getColor(gradientKey);
        // Unset gradient stops read as 0.
        return stop == 0 || inkOn(stop, textColor, red) == ink;
    }

    /**
     * The red for text on {@code surface}, or 0.
     *
     * <p>First a sanity check that costs nothing: the bubble's own text must
     * be clearly lighter or darker than the surface we think it is on. When it
     * is not, the colours we looked up are not the ones on screen (a per-chat
     * theme resolves through a ResourcesProvider a span cannot see) and
     * nothing measured against them means anything.
     */
    private static int inkOn(int surface, int textColor, int red) {
        surface |= 0xFF000000;
        if (Math.abs(luma(textColor) - luma(surface)) < 64) {
            return 0;
        }
        final int[] table = inks;
        for (int i = 0; i + 2 < table.length; i += 3) {
            if (table[i] == surface && table[i + 1] == red) {
                return table[i + 2];
            }
        }
        final int ink = shadeFor(surface, red);
        // A theme switch animates through hundreds of in-between colours;
        // start over rather than grow.
        final int keep = table.length >= INK_CACHE * 3 ? 0 : table.length;
        final int[] next = new int[keep + 3];
        System.arraycopy(table, 0, next, 0, keep);
        next[keep] = surface;
        next[keep + 1] = red;
        next[keep + 2] = ink;
        inks = next;
        return ink;
    }

    /** The themed red, or the nearest shade of it that is legible on {@code surface}; 0 if none. */
    private static int shadeFor(int surface, int red) {
        if (ColorUtils.calculateContrast(red, surface) >= MIN_CONTRAST) {
            return red;
        }
        final int towards = ColorUtils.calculateLuminance(surface) < ColorUtils.calculateLuminance(red)
                ? 0xFFFFFFFF : 0xFF000000;
        for (int step = 1; step <= MAX_SHADE_STEPS; step++) {
            final int shade = ColorUtils.blendARGB(red, towards, step / 10f) | 0xFF000000;
            if (ColorUtils.calculateContrast(shade, surface) >= MIN_CONTRAST) {
                return shade;
            }
        }
        return 0;
    }

    /**
     * The themed red as a fill under {@code ink}: itself where the ink reads
     * on it, otherwise the nearest shade of it — deeper under light ink,
     * paler under dark — on which it does. Both colours come from the theme;
     * a theme in which even the last shade fails gets that shade.
     */
    private static int blockFillFor(int red, int ink) {
        final int[] cached = blockFill;
        if (cached.length == 3 && cached[0] == red && cached[1] == ink) {
            return cached[2];
        }
        int fill = red;
        if (ColorUtils.calculateContrast(ink, fill) < MIN_CONTRAST) {
            final int towards = ColorUtils.calculateLuminance(ink) > ColorUtils.calculateLuminance(red)
                    ? 0xFF000000 : 0xFFFFFFFF;
            for (int step = 1; step <= MAX_FILL_STEPS; step++) {
                fill = ColorUtils.blendARGB(red, towards, step / 10f) | 0xFF000000;
                if (ColorUtils.calculateContrast(ink, fill) >= MIN_CONTRAST) {
                    break;
                }
            }
        }
        blockFill = new int[]{red, ink, fill};
        return fill;
    }

    /** Rec. 601 luma, 0–255. Integer-only: this one runs on every flagged span, every frame. */
    private static int luma(int color) {
        return (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) >> 8;
    }

    private static int withAlpha(int color, int alpha) {
        return alpha >= 255 ? color : ColorUtils.setAlphaComponent(color, Math.max(0, alpha));
    }

    // ------------------------------------------------------------------
    // The gate
    // ------------------------------------------------------------------

    /**
     * How long an approved open may take to come back through the gate when it
     * goes to the network first (Browser's telegra.ph lookup, Instant View's
     * page request). Longer than that and the user is asked again.
     */
    private static final long APPROVAL_MS = 20_000L;

    /**
     * Once such an open has come back, how long the rest of it — Browser's own
     * nested calls, same UI-thread turn — may still pass on that approval.
     */
    private static final long APPROVAL_TAIL_MS = 1_500L;

    /**
     * How long the text of a tapped text link is kept for the warning. The
     * long-press menu sits between the tap and the open, so this is "how long
     * a menu stays up", not "how long a call takes".
     */
    private static final long SHOWN_TEXT_MS = 60_000L;

    /** Seconds the second confirmation stays inert for a site on the block list. */
    private static final int COUNTDOWN_SECONDS = 3;

    private static final Object LOCK = new Object();

    // Guarded by LOCK.
    private static String approvedKey;
    private static long approvedUntil;
    private static boolean approvedComesBackTwice;
    private static String shownKey;
    private static String shownText;
    private static long shownUntil;

    /** How many approved opens are on the UI thread's stack right now. UI thread only. */
    private static int proceeding;

    /**
     * The confirmation on screen now, the address it is about and the
     * Activity it was shown in, so a second tap on the same link while it is
     * up does not stack a second one. UI thread only.
     *
     * <p>The Activity is part of the record because the dialog alone cannot be
     * believed. These dialogs are shown directly, so LaunchActivity does not
     * dismiss them when it is destroyed (a font-size change, the task swiped
     * away with the push service keeping the process); a dialog that was never
     * dismissed goes on answering {@code isShowing()} with true, and is kept
     * alive by the notification observer it registered. Without the owner,
     * the link it was about could not be opened again until the process died.
     */
    private static WeakReference<AlertDialog> asking;
    private static String askingKey;
    private static WeakReference<Activity> askingOwner;

    /**
     * True when {@link #intercept} would ask about this address.
     *
     * <p>For {@code AlertsCreator.showOpenUrlAlert} and
     * {@code showOpenExternalBrowserAlert}: upstream's own "Open this link?"
     * is skipped when the guard is about to ask twice anyway. Nothing is lost
     * by that — the guard's first dialog shows the real host, its punycode
     * form, the whole address and what the message showed instead.
     */
    public static boolean willPrompt(String url) {
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (url == null || !isOn(context) || insideApprovedOpen()) {
                return false;
            }
            if (isApproved(keyOf(url), false)) {
                return false;
            }
            return judge(url, null) != null;
        } catch (Throwable t) {
            // Unsure: let upstream show its own dialog. The gate still runs
            // behind it; the worst case is one prompt too many.
            return false;
        }
    }

    /**
     * The gate in front of every way a web address can be opened.
     *
     * @return true when the guard took over — it shows the two confirmations
     *         and runs {@code proceed} on the UI thread only after both; false
     *         when the caller should carry on as before (trusted, not a web
     *         address, the feature off, or this very open already approved)
     *
     * <p>{@code proceed} must be the original call with the original
     * arguments: the guard adds a question in front of upstream's decision
     * about custom tabs, the in-app browser and the rest, and must not make
     * that decision differently.
     *
     * <h3>Coming back through the gate</h3>
     *
     * {@code proceed} lands in the same function that called us, and that
     * function calls others that are gated too. While {@code proceed} is on
     * the stack every gate stands open — that is one approved open, however
     * upstream rewrites the address on the way (scheme case, login tokens).
     * Nothing outlives it: the next tap on the same link asks again.
     *
     * <p>The exception is an open that goes to the network before it opens
     * anything and re-enters later: Browser's telegra.ph lookup, and links
     * inside an Instant View page ({@link #interceptDeferred}). For those the
     * approval is kept, keyed by the cleaned address, for
     * {@link #APPROVAL_MS} at most and until it has been used.
     */
    public static boolean intercept(Context context, String url, Runnable proceed) {
        return gate(context, url, proceed, false);
    }

    /**
     * {@link #intercept} for a caller whose {@code proceed} asks the network
     * first and opens the address from the answer — {@code ArticleViewer}.
     */
    public static boolean interceptDeferred(Context context, String url, Runnable proceed) {
        return gate(context, url, proceed, true);
    }

    private static boolean gate(Context context, String url, Runnable proceed, boolean deferred) {
        if (url == null || proceed == null) {
            return false;
        }
        final Context app = context != null ? context : ApplicationLoader.applicationContext;
        if (!isOn(app) || insideApprovedOpen()) {
            return false;
        }
        final String key = keyOf(url);
        Finding finding;
        try {
            if (isApproved(key, true)) {
                return false;
            }
            finding = judge(url, shownTextFor(key));
        } catch (Throwable t) {
            finding = looksLikeWeb(url) ? new Finding(null, false, null) : null;
        }
        if (finding == null) {
            return false;
        }
        final Activity activity = activityFor(context);
        if (activity == null) {
            // Nowhere to ask (a call from a service or the application
            // context with no screen up). Not opened, and nothing shown.
            return true;
        }
        final Finding found = finding;
        final Runnable ask = () -> showStepOne(activity, url, key, found, proceed, deferred);
        if (onUiThread()) {
            ask.run();
        } else {
            AndroidUtilities.runOnUIThread(ask);
        }
        return true;
    }

    /**
     * The switch and the engine. If the engine cannot even say whether it is
     * running, the switch alone decides: off stays off, on keeps asking.
     */
    private static boolean isOn(Context context) {
        try {
            return LinkGuard.isActive();
        } catch (Throwable t) {
            try {
                return context != null && HumogramConfig.isLinkGuard(context);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static boolean onUiThread() {
        return Looper.getMainLooper().getThread() == Thread.currentThread();
    }

    private static boolean insideApprovedOpen() {
        return onUiThread() && proceeding > 0;
    }

    /**
     * What an approval is kept under: the address with its scheme
     * lower-cased, so the raw string a span holds and the one Browser
     * re-enters with are the same key.
     */
    private static String keyOf(String url) {
        final int scheme = url.indexOf("://");
        if (scheme > 0 && scheme <= 8) {
            return url.substring(0, scheme).toLowerCase(Locale.ROOT) + url.substring(scheme);
        }
        return url;
    }

    /**
     * @param use false to only look (the caller is not opening anything yet)
     */
    private static boolean isApproved(String key, boolean use) {
        synchronized (LOCK) {
            if (approvedKey == null) {
                return false;
            }
            final long now = SystemClock.elapsedRealtime();
            if (now > approvedUntil) {
                approvedKey = null;
                return false;
            }
            if (!approvedKey.equals(key)) {
                return false;
            }
            if (use && !approvedComesBackTwice) {
                // It has come back. What is left of the approval covers the
                // rest of this one open and nothing after it.
                approvedUntil = Math.min(approvedUntil, now + APPROVAL_TAIL_MS);
            }
            return true;
        }
    }

    /**
     * Both confirmations given: open it.
     */
    private static void open(String url, String key, boolean deferred, Runnable proceed) {
        final boolean telegraph = goesThroughTelegraphLookup(url);
        synchronized (LOCK) {
            if (deferred || telegraph) {
                approvedKey = key;
                approvedUntil = SystemClock.elapsedRealtime() + APPROVAL_MS;
                // An Instant View link to a telegra.ph page returns through
                // Browser and then again from Browser's own lookup.
                approvedComesBackTwice = telegraph;
            } else {
                approvedKey = null;
            }
        }
        proceeding++;
        try {
            proceed.run();
        } finally {
            proceeding--;
        }
    }

    /**
     * The hosts for which {@code Browser.openUrl} asks Telegram for a cached
     * page first and re-enters itself when there is none. Mirrors the test in
     * Browser; a miss here only means the user is asked again.
     */
    private static boolean goesThroughTelegraphLookup(String url) {
        try {
            final String host = Uri.parse(url).getHost();
            return host != null && (host.equalsIgnoreCase("telegra.ph") || host.equalsIgnoreCase("te.legra.ph")
                    || host.equalsIgnoreCase("graph.org") || host.equalsIgnoreCase("telegram.org"));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * An Activity to show the dialogs in. Callers pass whatever Context they
     * have — a view's, a fragment's, sometimes the application's.
     */
    private static Activity activityFor(Context context) {
        Activity activity = AndroidUtilities.findActivity(context);
        if (!isAlive(activity)) {
            activity = LaunchActivity.instance;
        }
        return isAlive(activity) ? activity : null;
    }

    private static boolean isAlive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    // ------------------------------------------------------------------
    // What the message showed
    // ------------------------------------------------------------------

    /**
     * Remember what a tapped text link showed, for the warning.
     *
     * <p>A text link ({@code URLSpanReplacement}) shows one thing and opens
     * another, and upstream's own dialog about that is skipped when the guard
     * asks instead — so the guard has to be able to say it. The span does not
     * travel with the address through Browser; this carries its visible text
     * across, keyed by the address. Any other span clears it, so a plain link
     * to the same address tapped a moment later is not blamed for it.
     *
     * <p>From {@code ChatActivity.didPressMessageUrl}, for every pressed span.
     */
    public static void noteTapped(CharacterStyle span, MessageObject message) {
        try {
            if (!(span instanceof URLSpanReplacement) || message == null) {
                forgetShownText();
                return;
            }
            String text = textUnder(span, message.messageText);
            if (text == null) {
                text = textUnder(span, message.caption);
            }
            if (text == null) {
                text = textUnder(span, message.linkDescription);
            }
            rememberShownText(((URLSpanReplacement) span).getURL(), text);
        } catch (Throwable t) {
            forgetShownText();
        }
    }

    /** The same, from {@code URLSpanReplacement.onClick} — text links outside a chat. */
    public static void noteTapped(CharacterStyle span, View widget) {
        try {
            if (!(span instanceof URLSpanReplacement) || !(widget instanceof TextView)) {
                forgetShownText();
                return;
            }
            rememberShownText(((URLSpanReplacement) span).getURL(),
                    textUnder(span, ((TextView) widget).getText()));
        } catch (Throwable t) {
            forgetShownText();
        }
    }

    private static String textUnder(Object span, CharSequence text) {
        if (!(text instanceof Spanned)) {
            return null;
        }
        final Spanned spanned = (Spanned) text;
        final int start = spanned.getSpanStart(span);
        final int end = spanned.getSpanEnd(span);
        if (start < 0 || end <= start) {
            return null;
        }
        return spanned.subSequence(start, end).toString();
    }

    private static void rememberShownText(String url, String text) {
        if (url == null || text == null || text.trim().isEmpty()) {
            forgetShownText();
            return;
        }
        final String key = keyOf(url);
        synchronized (LOCK) {
            shownKey = key;
            shownText = text;
            shownUntil = SystemClock.elapsedRealtime() + SHOWN_TEXT_MS;
        }
    }

    private static void forgetShownText() {
        synchronized (LOCK) {
            shownKey = null;
            shownText = null;
        }
    }

    private static String shownTextFor(String key) {
        synchronized (LOCK) {
            if (shownKey == null || !shownKey.equals(key) || SystemClock.elapsedRealtime() > shownUntil) {
                return null;
            }
            return shownText;
        }
    }

    // ------------------------------------------------------------------
    // The verdict
    // ------------------------------------------------------------------

    /** What the dialogs are about. */
    private static final class Finding {
        /** Null when the engine failed and the address is treated as unknown. */
        final LinkDecision decision;
        final boolean dangerous;
        /** What the message showed in place of the address, or null. */
        final String shown;

        Finding(LinkDecision decision, boolean dangerous, String shown) {
            this.decision = decision;
            this.dangerous = dangerous;
            this.shown = shown;
        }

        /** The host as the user should read it; "" when there is none. */
        String host() {
            final String host = decision != null ? decision.getDisplayHost() : null;
            return host != null ? host : "";
        }

        /** The host as it goes on the wire (punycode); "" when there is none. */
        String asciiHost() {
            final String host = decision != null ? decision.getAsciiHost() : null;
            return host != null ? host : "";
        }
    }

    /**
     * The guard's finding for this address, or null when it has nothing to
     * say: trusted, not a web address, or the guard not running.
     */
    private static Finding judge(String url, String shown) {
        final LinkDecision decision;
        try {
            decision = LinkGuard.decide(url, shown);
        } catch (Throwable t) {
            // The class name only: the message of an exception thrown while
            // parsing an address tends to contain the address.
            FileLog.e("link guard: decide failed: " + t.getClass().getSimpleName());
            return looksLikeWeb(url) ? new Finding(null, false, shown) : null;
        }
        if (decision == null) {
            return null;
        }
        final LinkTier tier = decision.getTier();
        if (tier == LinkTier.DANGEROUS) {
            return new Finding(decision, true, shown);
        }
        if (tier == LinkTier.UNKNOWN) {
            return new Finding(decision, false, shown);
        }
        return null;
    }

    /**
     * For the path where the engine could not answer: is this an address a
     * browser would open? tg:, mailto:, tel: and the like must never be held
     * up because of our own failure.
     */
    private static boolean looksLikeWeb(String url) {
        try {
            final String scheme = Uri.parse(url).getScheme();
            if (scheme == null) {
                return url.indexOf('.') > 0;
            }
            // TON sites open in the in-app browser like any page, and the core
            // judges them as web addresses. "evil.example:8080/x" parses with
            // its host as the "scheme".
            return scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")
                    || scheme.equalsIgnoreCase("tonsite") || scheme.indexOf('.') >= 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** One sentence: why this site is being asked about. */
    private static String reasonText(Context context, Finding finding) {
        final String unknown = JacStrings.get(context, R.string.jac_linkguard_reason_unknown);
        final LinkDecision decision = finding.decision;
        final LinkReason reason = decision != null ? decision.getReason() : null;
        if (reason == null) {
            return unknown;
        }
        switch (reason) {
            case DENYLISTED:
                return denyText(context, decision.getDenyReason());
            case IMPERSONATION:
                return explain(context, decision, JacStrings.get(context, R.string.jac_linkguard_reason_impersonation));
            case DECEPTIVE_URL:
                return explain(context, decision, JacStrings.get(context, R.string.jac_reason_userinfo));
            case LOOKS_LIKE_BRAND:
                return explain(context, decision, unknown);
            case FORCED_UNKNOWN:
                return JacStrings.get(context, R.string.jac_linkguard_reason_excluded);
            case UNPARSEABLE:
                return JacStrings.get(context, R.string.jac_linkguard_reason_unreadable);
            default:
                return finding.dangerous ? JacStrings.get(context, R.string.jac_linkguard_reason_blocked) : unknown;
        }
    }

    /** The scanner's own signals, in the words {@link ScanUi} already has for them. */
    private static String explain(Context context, LinkDecision decision, String fallback) {
        final List<String> codes = decision.getSignalCodes();
        final List<String> args = decision.getSignalArgs();
        if (codes == null || codes.isEmpty()) {
            return fallback;
        }
        return ScanUi.explainLink(context, codes.toArray(new String[0]),
                args != null ? args.toArray(new String[0]) : new String[0], fallback);
    }

    /**
     * Why the site is on the block list. The list carries a short code; free
     * text an administrator typed instead is not shown — it is in whatever
     * language they wrote it in, and the plain sentence is always true.
     */
    private static String denyText(Context context, String denyReason) {
        final String code = denyReason != null ? denyReason.trim().toLowerCase(Locale.ROOT) : "";
        switch (code) {
            case "phishing":
                return JacStrings.get(context, R.string.jac_linkguard_reason_blocked_phishing);
            case "malware":
                return JacStrings.get(context, R.string.jac_linkguard_reason_blocked_malware);
            case "scam":
                return JacStrings.get(context, R.string.jac_linkguard_reason_blocked_scam);
            case "ip_grabber":
                return JacStrings.get(context, R.string.jac_linkguard_reason_blocked_ip_grabber);
            default:
                return JacStrings.get(context, R.string.jac_linkguard_reason_blocked);
        }
    }

    /**
     * The line about what the message showed, or null when there is nothing to
     * say. Two different sentences: text that itself reads as an address and
     * names a different site is a disguise and is called one; ordinary words
     * ("read more") are just how text links work, and the line only says the
     * address was behind them.
     */
    private static String shownLine(Context context, Finding finding) {
        if (finding.shown == null) {
            return null;
        }
        final String shown = clip(finding.shown, 80);
        if (shown.isEmpty()) {
            return null;
        }
        final String shownHost = hostIn(shown);
        if (shownHost == null) {
            return JacStrings.get(context, R.string.jac_linkguard_hidden_text, shown);
        }
        if (sameSite(shownHost, finding.asciiHost())) {
            return null;
        }
        return JacStrings.get(context, R.string.jac_link_display_mismatch, shown);
    }

    /** The host, if {@code text} reads as a web address; null if it is just words. */
    private static String hostIn(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return null;
            }
        }
        String rest = text;
        final int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        for (int i = 0; i < rest.length(); i++) {
            final char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                rest = rest.substring(0, i);
                break;
            }
        }
        final int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        final int port = rest.lastIndexOf(':');
        if (port > 0) {
            rest = rest.substring(0, port);
        }
        final int dot = rest.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        // "v1.2" and "10.30" have a dot too. A name ends in letters (or in
        // the punycode of letters), at least two of them.
        final String ending = rest.substring(rest.lastIndexOf('.') + 1);
        if (ending.length() < 2) {
            return null;
        }
        if (!ending.regionMatches(true, 0, "xn--", 0, 4)) {
            for (int i = 0; i < ending.length(); i++) {
                if (!Character.isLetter(ending.charAt(i))) {
                    return null;
                }
            }
        }
        return rest;
    }

    private static boolean sameSite(String shownHost, String asciiHost) {
        String shown = shownHost;
        try {
            shown = IDN.toASCII(shownHost, IDN.ALLOW_UNASSIGNED);
        } catch (Throwable ignored) {
            // Not a name IDN accepts; compare it as written.
        }
        return !asciiHost.isEmpty() && withoutWww(shown).equalsIgnoreCase(withoutWww(asciiHost));
    }

    private static String withoutWww(String host) {
        return host.regionMatches(true, 0, "www.", 0, 4) ? host.substring(4) : host;
    }

    /** One line, no longer than {@code max}: this goes inside a sentence in a dialog. */
    private static String clip(String text, int max) {
        final String line = text.replaceAll("\\s+", " ").trim();
        return line.length() <= max ? line : line.substring(0, max - 1).trim() + "…";
    }

    // ------------------------------------------------------------------
    // The two confirmations
    // ------------------------------------------------------------------

    /**
     * Step 1 of 2 — what the site is.
     *
     * <p>The safe way out is the positive button, where the thumb already is;
     * the way through is the quieter one, in red. It shows the host the link
     * really goes to — Unicode as the user reads it, and the punycode form
     * underneath when the two differ, which is the whole tell of a look-alike
     * name — and under that the whole address, when there is more to it than
     * the host.
     */
    private static void showStepOne(Activity activity, String url, String key, Finding finding,
                                    Runnable proceed, boolean deferred) {
        try {
            if (!isAlive(activity) || alreadyAsking(activity, key)) {
                return;
            }
            // The list may have moved since this link was coloured. Ask for a
            // fresh one now (throttled by the engine, never blocking); step two
            // judges the address again.
            try {
                LinkGuard.refresh();
            } catch (Throwable ignored) {
            }

            final SpannableStringBuilder message = new SpannableStringBuilder(reasonText(activity, finding));
            final String shown = shownLine(activity, finding);
            if (shown != null) {
                message.append("\n\n").append(shown);
            }

            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTopImage(glyph(finding.dangerous ? JacIcons.Glyph.BLOCK : JacIcons.Glyph.SHIELD), bandColor())
                    .setTitle(JacStrings.get(activity, finding.dangerous
                            ? R.string.jac_linkguard_dangerous_title : R.string.jac_linkguard_unknown_title))
                    .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 1, 2))
                    .setMessage(message)
                    .setView(addressView(activity, finding, url, key))
                    .setPositiveButton(JacStrings.get(activity, R.string.jac_link_back), null)
                    .setNegativeButton(JacStrings.get(activity, R.string.jac_link_continue),
                            (d, which) -> showStepTwo(activity, url, key, finding, proceed, deferred))
                    .setOnPreDismissListener(LinkGuardUi::noLongerAsking)
                    .create();
            dialog.show();
            nowAsking(dialog, key, activity);
            colourButton(dialog, DialogInterface.BUTTON_NEGATIVE, Theme.getColor(Theme.key_text_RedBold));
            if (finding.dangerous) {
                // The siren a dangerous installer gets (JacAlarm): a site on
                // the block list is the link-shaped version of that moment.
                // Unknown sites stay silent — they are most links, and a
                // siren that sounds on most links is one people stop hearing.
                JacAlarm.sound(activity);
            }
        } catch (Throwable t) {
            // Could not ask, so not opened.
            FileLog.e("link guard: step one failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Step 2 of 2 — what can happen to you.
     *
     * <p>Not reachable except through step one. It will not proceed on a tap:
     * the red button is inert until the checkbox is ticked, and for a site on
     * the block list both buttons are also inert for
     * {@link #COUNTDOWN_SECONDS}, so the second half of a double-tap cannot
     * land on either.
     *
     * <p>The address is judged again first. If the list that arrived while
     * step one was up now trusts the site, it simply opens; if it now blocks
     * it, this is the block-list version of the step, and says so.
     */
    private static void showStepTwo(Activity activity, String url, String key, Finding first,
                                    Runnable proceed, boolean deferred) {
        try {
            if (!isAlive(activity)) {
                return;
            }
            final Finding now = isOn(activity) ? judge(url, first.shown) : null;
            if (now == null) {
                open(url, key, deferred, proceed);
                return;
            }

            // Telegram's own dialog checkbox, configured the way AlertsCreator
            // and QuarantineDialogs configure it.
            final CheckBoxCell acknowledge = new CheckBoxCell(activity, 1);
            acknowledge.setMultiline(true);
            acknowledge.getTextView().getLayoutParams().width = LayoutHelper.MATCH_PARENT;
            acknowledge.getTextView().setSingleLine(false);
            acknowledge.getTextView().setMaxLines(3);
            acknowledge.getTextView().setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            acknowledge.setText(JacStrings.get(activity, R.string.jac_linkguard_confirm_checkbox), "", false, false);

            final String site = now.host().isEmpty() ? clip(url, 60) : now.host();
            final SpannableStringBuilder message = new SpannableStringBuilder();
            if (now.dangerous && !first.dangerous) {
                message.append(JacStrings.get(activity, R.string.jac_linkguard_now_blocked)).append("\n\n");
            }
            final int bodyStart = message.length();
            message.append(JacStrings.get(activity, now.dangerous
                    ? R.string.jac_linkguard_confirm_dangerous : R.string.jac_linkguard_confirm_unknown, site));
            final int siteAt = message.toString().indexOf(site, bodyStart);
            if (siteAt >= 0) {
                message.setSpan(new TypefaceSpan(AndroidUtilities.bold()), siteAt, siteAt + site.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTopImage(glyph(JacIcons.Glyph.WARNING), bandColor())
                    .setTitle(JacStrings.get(activity, R.string.jac_linkguard_confirm_title))
                    .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 2, 2))
                    .setMessage(message)
                    .setView(acknowledge)
                    .setPositiveButton(JacStrings.get(activity, R.string.jac_link_continue_confirm_action),
                            (d, which) -> open(url, key, deferred, proceed))
                    .setNegativeButton(JacStrings.get(activity, R.string.jac_quarantine_confirm_cancel), null)
                    .setOnPreDismissListener(LinkGuardUi::noLongerAsking)
                    .create();
            dialog.show();
            nowAsking(dialog, key, activity);
            if (now.dangerous && !first.dangerous) {
                // Blocked while step one was up: the siren step one did not play.
                JacAlarm.sound(activity);
            }

            final View danger = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            colourButton(dialog, DialogInterface.BUTTON_POSITIVE, Theme.getColor(Theme.key_text_RedBold));
            // Disabled until acknowledged, and visibly so (the dialog's own
            // buttons dim themselves when disabled).
            final boolean[] held = {now.dangerous};
            setEnabled(danger, false);
            acknowledge.setOnClickListener(v -> {
                acknowledge.setChecked(!acknowledge.isChecked(), true);
                setEnabled(danger, acknowledge.isChecked() && !held[0]);
            });
            if (now.dangerous) {
                countDown(dialog, () -> {
                    held[0] = false;
                    setEnabled(danger, acknowledge.isChecked());
                });
            }
        } catch (Throwable t) {
            FileLog.e("link guard: step two failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Whether a confirmation about this address is up in this Activity right
     * now. One that is recorded but is not that — its Activity is gone, or it
     * belongs to another — is let go of here, so that the tap being handled
     * gets its question.
     */
    private static boolean alreadyAsking(Activity activity, String key) {
        final AlertDialog dialog = asking != null ? asking.get() : null;
        if (dialog == null || !key.equals(askingKey)) {
            return false;
        }
        final Activity owner = askingOwner != null ? askingOwner.get() : null;
        if (dialog.isShowing() && owner == activity && isAlive(owner)) {
            return true;
        }
        asking = null;
        askingKey = null;
        askingOwner = null;
        try {
            // Its window may be gone already, which upstream's dismiss()
            // survives; what matters is that it also takes the dialog off the
            // notification observer list, which is what was keeping it (and
            // the dead Activity) alive.
            dialog.dismiss();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void nowAsking(AlertDialog dialog, String key, Activity activity) {
        asking = new WeakReference<>(dialog);
        askingKey = key;
        askingOwner = new WeakReference<>(activity);
    }

    /**
     * From the dialog's pre-dismiss hook — AlertDialog's own, which runs at the
     * top of {@code dismiss()}; the platform's OnDismissListener is not
     * delivered for a window that was already torn down. Only the dialog on
     * record clears the record: step one is dismissed after step two has been
     * shown and recorded in its place.
     */
    private static void noLongerAsking(DialogInterface dialog) {
        if (asking != null && asking.get() == dialog) {
            asking = null;
            askingKey = null;
            askingOwner = null;
        }
    }

    /**
     * Hold the safe button for {@link #COUNTDOWN_SECONDS}, counting down on
     * it. On the safe button for the reason {@link QuarantineDialogs} gives: it
     * is the one the user should end up pressing, so it is the one that
     * explains the wait. Back and a tap outside still close the dialog — that
     * is the safe direction and is never held.
     */
    private static void countDown(AlertDialog dialog, Runnable whenOver) {
        final View cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (!(cancel instanceof TextView)) {
            whenOver.run();
            return;
        }
        final TextView safe = (TextView) cancel;
        final String label = safe.getText().toString();
        final int[] remaining = {COUNTDOWN_SECONDS};
        setEnabled(safe, false);
        safe.setText(label + "  " + remaining[0]);

        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            // Dismissed, or the activity torn down under it: just stop.
            if (!dialog.isShowing()) {
                return;
            }
            remaining[0]--;
            if (remaining[0] > 0) {
                safe.setText(label + "  " + remaining[0]);
                safe.postDelayed(tick[0], 1000L);
            } else {
                safe.setText(label);
                setEnabled(safe, true);
                whenOver.run();
            }
        };
        safe.postDelayed(tick[0], 1000L);
    }

    /**
     * Where the link really goes, in the pill upstream's own "Open this link?"
     * dialog shows an address in (AlertsCreator.showOpenUrlAlert): the host in
     * full — never ellipsised, the end of a long host is the part that
     * matters — the punycode form under it when the two differ, and under
     * those the whole address when it says more than the host does.
     *
     * <p>The host stays the headline. In {@code https://bank.uz@evil.example/}
     * it is the parsed host that says where the link goes, and the full
     * address that is built to mislead. But the full address has to be there:
     * upstream's dialog, which the guard replaces, showed it, and for a
     * document or a form on a shared host the path is the only thing that
     * tells one link from another.
     *
     * @param key the address as the open will carry it (see {@link #keyOf})
     */
    private static View addressView(Context context, Finding finding, String url, String key) {
        final String host = finding.host();
        final String ascii = finding.asciiHost();

        final LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setPadding(dp(14), dp(12), dp(14), dp(12));
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(22));
        background.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack), 0.06f));
        pill.setBackground(background);

        final TextView hostView = new TextView(context);
        // An address that could not be read has no host to show; the raw
        // address, cut to a few lines, is the honest substitute.
        hostView.setText(host.isEmpty() ? clip(url, 160) : host);
        hostView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        hostView.setTypeface(AndroidUtilities.bold());
        hostView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        hostView.setGravity(Gravity.CENTER);
        pill.addView(hostView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!ascii.isEmpty() && !ascii.equalsIgnoreCase(host)) {
            final TextView asciiView = new TextView(context);
            asciiView.setText(ascii);
            asciiView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            asciiView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
            asciiView.setGravity(Gravity.CENTER);
            pill.addView(asciiView, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }

        // With no host the first line is already the raw address.
        final String address = host.isEmpty() ? null : addressBeyondHost(key, host, ascii);
        if (address != null) {
            final TextView addressView = new TextView(context);
            addressView.setText(address);
            addressView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            addressView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
            addressView.setGravity(Gravity.CENTER);
            addressView.setMaxLines(4);
            addressView.setEllipsize(TextUtils.TruncateAt.END);
            pill.addView(addressView, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(pill, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 4, 22, 9));
        return container;
    }

    /** More than four lines of small text can show; the rest would only be laid out to be cut. */
    private static final int ADDRESS_MAX_CHARS = 400;

    /**
     * The whole address, if it carries anything the host line does not — a
     * path, a query, a fragment, a port, a name before an "@" — otherwise
     * null. Compared as text rather than parsed: whatever is left of the
     * address once the scheme and one trailing slash are taken off either is
     * the host or is more than the host, and "more" is shown.
     */
    private static String addressBeyondHost(String key, String host, String ascii) {
        if (key == null) {
            return null;
        }
        String rest = key;
        final int scheme = rest.indexOf("://");
        if (scheme > 0) {
            rest = rest.substring(scheme + 3);
        }
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        if (rest.equalsIgnoreCase(host) || rest.equalsIgnoreCase(ascii)) {
            return null;
        }
        return withoutReordering(clip(key, ADDRESS_MAX_CHARS));
    }

    /**
     * The text with its bidirectional control characters made visible. They
     * draw nothing themselves and reorder what follows, which in an address
     * shown so that it can be checked is a way to make it read as another.
     */
    private static String withoutReordering(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == 0x061C || c == 0x200E || c == 0x200F || (c >= 0x202A && c <= 0x202E)
                    || (c >= 0x2066 && c <= 0x2069)) {
                if (out == null) {
                    out = new StringBuilder(text);
                }
                out.setCharAt(i, (char) 0xFFFD);
            }
        }
        return out != null ? out.toString() : text;
    }

    /** A verdict glyph for the dialog's top band, in the white upstream draws on filled buttons. */
    private static DeviceScanGlyphDrawable glyph(JacIcons.Glyph glyph) {
        return new DeviceScanGlyphDrawable(glyph, 52, Theme.key_featuredStickers_buttonText);
    }

    /**
     * The band behind that glyph: the same red a blocked link's block is
     * filled with in a chat, so the dialog is recognisably about that link —
     * and for the same reason, the themed red where the glyph's ink reads on
     * it and a deeper shade of it where it does not.
     */
    private static int bandColor() {
        return blockFillFor(Theme.getColor(Theme.key_text_RedRegular) | 0xFF000000,
                Theme.getColor(Theme.key_featuredStickers_buttonText) | 0xFF000000);
    }

    private static void colourButton(AlertDialog dialog, int button, int color) {
        final View view = dialog.getButton(button);
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(color);
        }
    }

    private static void setEnabled(View button, boolean enabled) {
        if (button != null) {
            button.setEnabled(enabled);
        }
    }
}
