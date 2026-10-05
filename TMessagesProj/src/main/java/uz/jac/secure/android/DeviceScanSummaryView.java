package uz.jac.secure.android;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CacheChart;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The verdict at the top of the virus scanner page, drawn the way Telegram
 * draws "Storage cleared": the same {@link CacheChart} ring, spinning while
 * the scan runs and settling into a green tick with stars when nothing was
 * found — or, for threats, the same ring in red with an exclamation mark.
 * Under it a bold animated title and a grey line, laid out like the storage
 * screen's chart header.
 *
 * <h3>Why upstream's ring and not our own</h3>
 *
 * The page used to draw its own glyph in a tinted disc. It worked, and it
 * looked like a different app. People know the storage screen's ring: it
 * animates while something is being counted and lands on a tick when the job
 * is done. A scan is exactly that, so the scanner speaks the same visual
 * language — same geometry, same easing, same gradient — instead of inventing
 * a second one. The only thing upstream did not have was a red variant, added
 * as {@link CacheChart#setCompleteStyle} with defaults that reproduce upstream.
 *
 * <h3>What it does not decide</h3>
 *
 * Nothing here knows about reports or strings. {@code DeviceScanSection}
 * picks the state and the words; this class only shows them. It sits in the
 * list as {@code UItem.asCustomShadow}, the item upstream uses for content
 * that stands on the page background rather than inside a section card.
 */
public class DeviceScanSummaryView extends LinearLayout {

    public static final int STATE_LOADING = 0;
    public static final int STATE_CLEAN = 1;
    public static final int STATE_ALERT = 2;
    public static final int STATE_WARNING = 3;
    public static final int STATE_IDLE = 4;

    /** Upstream's "done" gradient (CacheChart's constructor), kept so a clean result looks exactly like it. */
    private static final int GREEN_TOP = 0xFF6ED556;
    private static final int GREEN_BOTTOM = 0xFF41BA71;

    private CacheChart chart;
    private final AnimatedTextView title;
    private final TextView subtitle;

    private int state = -1;
    /** The chart has left its loading state; a new scan needs a fresh one. */
    private boolean chartSettled;

    public DeviceScanSummaryView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setClipChildren(false);

        chart = newChart(context);
        addView(chart, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Same type ramp as the storage screen's chart header; character-level
        // diffing as CacheChart's own counters use, so "34 / 120" rolls its digits.
        title = new AnimatedTextView(context, false, true, true);
        title.setAnimationProperties(.35f, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(AndroidUtilities.dp(20));
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 28, Gravity.CENTER_HORIZONTAL, 24, 0, 24, 0));

        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(AndroidUtilities.dp(2), 1f);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        subtitle.setMaxLines(4);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 6, 32, 18));
    }

    private static CacheChart newChart(Context context) {
        CacheChart chart = new CacheChart(context);
        // Display only: the storage screen's sector taps mean nothing here.
        chart.setInterceptTouch(false);
        chart.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return chart;
    }

    /**
     * Show a state. Repeating the current state only updates the words, so
     * the progress ticks of a running scan roll the title's digits instead of
     * replaying the ring.
     */
    public void bind(int newState, CharSequence titleText, CharSequence subtitleText) {
        title.setText(titleText == null ? "" : titleText, state != -1);
        subtitle.setText(subtitleText);
        subtitle.setVisibility(TextUtils.isEmpty(subtitleText) ? GONE : VISIBLE);
        setContentDescription(subtitleText == null ? titleText : titleText + ". " + subtitleText);
        if (newState == state) {
            return;
        }
        state = newState;

        if (newState == STATE_LOADING) {
            if (chartSettled) {
                // CacheChart only spins from construction; a rescan gets a new one.
                removeView(chart);
                chart = newChart(getContext());
                addView(chart, 0, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                chartSettled = false;
            }
            return;
        }

        switch (newState) {
            case STATE_CLEAN:
                chart.setCompleteStyle(CacheChart.COMPLETE_ICON_CHECK, GREEN_TOP, GREEN_BOTTOM, true);
                break;
            case STATE_ALERT:
                setAlert(Theme.getColor(Theme.key_text_RedRegular));
                break;
            case STATE_WARNING:
                setAlert(Theme.getColor(Theme.key_color_orange));
                break;
            case STATE_IDLE:
            default:
                setAlert(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
                break;
        }
        // No segments and a zero total is CacheChart's "done": it animates
        // out of the spinner into the finished ring.
        chart.setSegments(0, true);
        chartSettled = true;
    }

    /** A lighter top fading into the colour itself, the shape of upstream's green gradient. */
    private void setAlert(int color) {
        final int top = ColorUtils.blendARGB(color, Color.WHITE, .22f);
        chart.setCompleteStyle(CacheChart.COMPLETE_ICON_ALERT, top, color, false);
    }
}
