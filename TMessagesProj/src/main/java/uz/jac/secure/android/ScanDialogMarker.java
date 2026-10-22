package uz.jac.secure.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;

import uz.jac.secure.core.model.Verdict;

/**
 * The scan marker on a chat-list row.
 *
 * <h3>What it is for</h3>
 *
 * Someone who has not opened the app since yesterday should be able to see,
 * from the list alone, that one conversation contains something that was
 * blocked. Without a marker here the warning exists only inside the chat, which
 * means it is found by opening the chat — and the tap that opens a chat is the
 * same reflex that opens the file in it.
 *
 * <h3>Two levels of loudness, and why not three</h3>
 *
 * <ul>
 *   <li><b>Blocked and suspicious</b> get a filled chip that <em>replaces</em>
 *       the preview line, plus a tint and an edge stripe on the row. The
 *       preview text is the sender's own words about the file, and on a
 *       malicious message that text is the lure — "your account is frozen,
 *       install this". Leaving it next to our warning gives the attacker the
 *       larger share of the row.</li>
 *   <li><b>Clean and device-checked</b> get a small glyph in front of the
 *       normal preview, and nothing else. A green tick on every row is a
 *       decoration, and decorations are what the eye learns to skip — which is
 *       precisely the habit that has to survive for the red chip to work.</li>
 * </ul>
 *
 * There is no third level. The tint is the only background the row ever gets;
 * suspicious and blocked differ in colour, not in intensity, because a
 * three-step scale is not something anyone reads at a glance.
 *
 * <h3>Contract</h3>
 *
 * Same shape as {@link ScanVerdictBlock}: the host {@code DialogCell} owns an
 * instance, binds it during layout, and calls {@link #draw} with the rectangle
 * where the preview line would have gone. Nothing here is a touch target — the
 * row keeps its single tap, which opens the chat.
 */
public final class ScanDialogMarker {

    private static final int GLYPH_DP = 14;
    private static final int CHIP_HEIGHT_DP = 20;
    private static final int SPINNER_PERIOD_MS = 900;

    private final Context context;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF glyphBounds = new RectF();
    private final RectF chipBounds = new RectF();

    private ScanUi.Presentation presentation;
    private boolean chip;
    private boolean animating;

    public ScanDialogMarker(Context context) {
        this.context = context;
        textPaint.setTextSize(JacTheme.sp(context, 13f));
        glyphPaint.setStyle(Paint.Style.STROKE);
    }

    /**
     * @return true when this row has a marker to draw
     */
    public boolean bind(ScanStateStore.State state) {
        presentation = ScanUi.present(context, state);
        if (presentation == null) {
            return false;
        }
        animating = state.scanning;
        chip = needsAttention(state);
        textPaint.setTypeface(chip
                ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
        return true;
    }

    /**
     * Bind a pre-screened link verdict instead of a file one.
     *
     * A row shows at most one marker, and a file verdict wins when both exist:
     * the file one comes from a full scan of something already on the device,
     * the link one from the offline half only — see {@link LinkGate#prescreen}.
     * The host cell should try {@link #bind} first and fall back to this.
     *
     * Red, like every warning about a threat (the owner's call, 2026-10-16);
     * the words stay "suspicious link", which is all this offline marker can
     * claim, and the glyph is the caution triangle rather than the virus.
     *
     * @return true when this row has a marker to draw
     */
    public boolean bindLink(uz.jac.secure.core.engine.LinkScanner.LinkVerdict verdict) {
        if (verdict == null || !verdict.getRequiresInterstitial()) {
            presentation = null;
            return false;
        }
        presentation = new ScanUi.Presentation(
                JacIcons.Glyph.WARNING,
                JacTheme.danger(context),
                JacStrings.get(context, org.telegram.messenger.R.string.jac_chip_link_suspicious),
                null,
                false,
                JacStrings.get(context, org.telegram.messenger.R.string.jac_chip_link_suspicious));
        animating = false;
        chip = true;
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return true;
    }

    /** Row tint for a link marker; red, and the same 8% wash as a file. */
    public static int linkRowTint(Context context) {
        return JacTheme.wash(JacTheme.danger(context), 8);
    }

    public static int linkRowStripe(Context context) {
        return JacTheme.danger(context);
    }

    /**
     * Does this row's marker replace the preview line, or sit in front of it?
     *
     * Static so the host cell can decide whether to build the preview text at
     * all before it builds the marker.
     */
    public static boolean needsAttention(ScanStateStore.State state) {
        if (state == null || state.scanning) {
            return false;
        }
        return state.verdict == Verdict.MALICIOUS || state.verdict == Verdict.SUSPICIOUS;
    }

    /**
     * Background wash for the whole row, or 0 for none.
     *
     * 8% is deliberately near the threshold of noticeable. It has to survive
     * being one row among seven on an OLED screen in daylight without turning
     * the list into a warning screen — the chip carries the message, the tint
     * only says "here".
     */
    public static int rowTint(Context context, ScanStateStore.State state) {
        if (!needsAttention(state)) {
            return 0;
        }
        // Red for both: suspicious is a threat too.
        return JacTheme.wash(JacTheme.danger(context), 8);
    }

    /** Colour of the leading edge stripe, or 0 for none. */
    public static int rowStripe(Context context, ScanStateStore.State state) {
        if (!needsAttention(state)) {
            return 0;
        }
        return JacTheme.danger(context);
    }

    /** Width the marker wants; the host gives the rest to the preview text. */
    public int measure(int maxWidth) {
        if (presentation == null) {
            return 0;
        }
        int glyph = JacTheme.dp(context, GLYPH_DP);
        if (!chip) {
            return glyph + JacTheme.dp(context, 5);
        }
        float text = textPaint.measureText(presentation.chip);
        int padded = (int) Math.ceil(text) + glyph + JacTheme.dp(context, 5) + JacTheme.dp(context, 14);
        return Math.min(padded, maxWidth);
    }

    /**
     * Draw at {@code left}, vertically centred on {@code centreY}.
     *
     * @return true if a redraw is needed for animation
     */
    public boolean draw(Canvas canvas, float left, float centreY, int maxWidth) {
        if (presentation == null) {
            return false;
        }

        int glyph = JacTheme.dp(context, GLYPH_DP);
        float x = left;

        if (chip) {
            int height = JacTheme.dp(context, CHIP_HEIGHT_DP);
            float padding = JacTheme.dp(context, 7);
            chipBounds.set(left, centreY - height / 2f, left + measure(maxWidth), centreY + height / 2f);

            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(JacTheme.wash(presentation.colour, 18));
            float radius = JacTheme.dp(context, 5);
            canvas.drawRoundRect(chipBounds, radius, radius, fillPaint);

            x = chipBounds.left + padding;
        }

        glyphBounds.set(x, centreY - glyph / 2f, x + glyph, centreY + glyph / 2f);
        glyphPaint.setColor(presentation.colour);
        boolean redraw = false;
        if (animating) {
            float angle = (SystemClock.elapsedRealtime() % SPINNER_PERIOD_MS) * 360f / SPINNER_PERIOD_MS;
            int saved = canvas.save();
            canvas.rotate(angle, glyphBounds.centerX(), glyphBounds.centerY());
            JacIcons.draw(canvas, presentation.glyph, glyphBounds, glyphPaint);
            canvas.restoreToCount(saved);
            redraw = true;
        } else {
            JacIcons.draw(canvas, presentation.glyph, glyphBounds, glyphPaint);
        }

        if (!chip) {
            // Glyph only: the host draws its own preview text after this, in
            // its own colour, starting at left + measure().
            return redraw;
        }

        textPaint.setColor(presentation.colour);
        float textLeft = glyphBounds.right + JacTheme.dp(context, 5);
        float available = chipBounds.right - JacTheme.dp(context, 7) - textLeft;
        CharSequence text = TextUtils.ellipsize(
                presentation.chip, textPaint, Math.max(0, available), TextUtils.TruncateAt.END);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = centreY - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(text, 0, text.length(), textLeft, baseline, textPaint);
        return redraw;
    }
}
