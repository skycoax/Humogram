package uz.jac.secure.android;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * "Device security" — the virus scanner's live status, as a card on
 * Telegram's own Settings screen.
 *
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │ Qurilma xavfsizligi                   ╭───╮  │
 * │ Tekshiruvlar bajarilmoqda…            │80%│  │
 * │                                       ╰───╯  │
 * └──────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Why it is here and not behind a row</h3>
 *
 * The scanner is the app's main feature. A row that says "Virus scanner" and
 * has to be opened before it says anything hides the one answer people want
 * from it — is my phone fine? — one tap away. So the answer sits on the main
 * Settings screen, under the Humogram row. The card opens Humogram's page,
 * where the scanner ({@link DeviceScanSection}) comes first, with the threat
 * list and the uninstall buttons.
 *
 * <h3>Built from Telegram's parts</h3>
 *
 * It is a {@link UItem.UItemFactory} item like {@code SettingsActivity.SettingCell},
 * so the list gives it the section card, the selector ripple, the click and the
 * TalkBack click action exactly as it gives them to every other row. The text is
 * a {@link TextView} and an {@link AnimatedTextView} in the row typography (16 /
 * 13); every colour comes from {@link Theme}, every size from {@code dp}.
 *
 * <p>The ring is the only thing drawn here, and it is drawn as a small member
 * of {@code CacheChart}'s family — the ring the scanner page itself shows: the
 * same stroke-to-diameter ratio, the same "done" green ({@code 0xFF6ED556 →
 * 0xFF41BA71}), the same check-mark and exclamation geometry, the same
 * {@code EASE_OUT_QUINT} easing. While a scan runs it is a faint track with an
 * orange arc and the percentage in the middle (no linear bar — that was
 * deliberately dropped from the design). When it ends the arc settles on the
 * protection percentage ({@link SecurityCheckup#score}: the scan's verdict and
 * the device check together), turning green, orange or red with the level and
 * keeping the number in the middle. Only when no percentage can be worked out
 * does it fall back to a full ring with the tick or the "!".
 *
 * <h3>Who decides what</h3>
 *
 * The view only shows a state. {@link Controller} — one per Settings screen,
 * owned by the fragment — follows {@link DeviceScanner}, reads the report
 * through {@link DeviceScanUi} (so "2 threats" here cannot disagree with the
 * page it opens), and starts a quick incremental scan when the screen comes
 * back after a while.
 */
public class DeviceScanCard extends FrameLayout implements Theme.Colorable {

    static final int MODE_EMPTY = 0;
    static final int MODE_SCANNING = 1;
    static final int MODE_CLEAN = 2;
    static final int MODE_THREATS = 3;
    static final int MODE_NEUTRAL = 4;

    private static final int HEIGHT_DP = 88;
    private static final int RING_DP = 64;
    /** CacheChart's finished ring is 10 on 172; the same ratio at this size. */
    private static final int STROKE_DP = 4;
    private static final int RING_MARGIN_DP = 16;
    /** Where SettingCell's text starts when it has no icon (18 + its 2 dp nudge). */
    private static final int TEXT_MARGIN_DP = 20;
    private static final int TEXT_GAP_DP = 12;
    private static final int RING_SPACE_DP = RING_MARGIN_DP + RING_DP + TEXT_GAP_DP;

    /** Upstream's "done" gradient (CacheChart's constructor), so a clean phone looks exactly like it. */
    private static final int GREEN_TOP = 0xFF6ED556;
    private static final int GREEN_BOTTOM = 0xFF41BA71;

    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView titleView;
    private final AnimatedTextView statusView;
    private final AnimatedTextView.AnimatedTextDrawable percentText;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GradientStroke arcPaint = new GradientStroke();
    private final GradientStroke ringPaint = new GradientStroke();
    private final RectF ringBounds = new RectF();
    private final RectF arcBounds = new RectF();
    private final Path checkPath = new Path();
    private final Path alertPath = new Path();
    private final RectF pathBounds = new RectF();

    private final AnimatedFloat sweep = new AnimatedFloat(0f, this, 0, 600, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat scanAlpha = new AnimatedFloat(0f, this, 0, 420, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat completeAlpha = new AnimatedFloat(0f, this, 0, 420, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat ringColor = new AnimatedFloat(1f, this, 0, 420, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat checkAlpha = new AnimatedFloat(0f, this, 0, 500, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat alertAlpha = new AnimatedFloat(0f, this, 0, 500, CubicBezierInterpolator.EASE_OUT_QUINT);
    /** 0: the scan's orange arc; 1: the protection gauge in its level's colour. */
    private final AnimatedFloat gaugeColor = new AnimatedFloat(0f, this, 0, 420, CubicBezierInterpolator.EASE_OUT_QUINT);

    private boolean hasState;
    /** The last protection percentage drawn, for the colour of a gauge fading out. */
    private int lastScore = -1;
    private int mode = MODE_EMPTY;
    private float progress;
    /** Protection percentage shown once a run has finished; -1 when unknown. */
    private int score = -1;
    /** The finished ring cross-fades from one verdict colour to the next. */
    private int ringFromMode = MODE_NEUTRAL;
    private int ringToMode = MODE_NEUTRAL;
    /** What the last frame drew, so a state change knows what is on screen. */
    private float lastScan;
    private float lastComplete;

    private String statusFull = "";
    private String statusCompact;
    private int statusColorKey = Theme.key_windowBackgroundWhiteGrayText;
    private int statusWidth;

    public DeviceScanCard(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);

        final boolean rtl = LocaleController.isRTL;
        final int textGravity = rtl ? Gravity.RIGHT : Gravity.LEFT;

        final LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine();
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(textGravity);
        titleView.setText(JacStrings.get(context, R.string.jac_seccard_title));
        titleView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Word diffing: "· 108 ta ilova" → "· 109 ta ilova" rolls only the
        // number, and a new verdict replaces the line as a whole.
        statusView = new AnimatedTextView(context, true, true, false);
        statusView.setAnimationProperties(.35f, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
        statusView.setTextSize(dp(13));
        statusView.setGravity(textGravity);
        statusView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        textLayout.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 18, 0, 2, 0, 0));

        addView(textLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | textGravity,
                rtl ? RING_SPACE_DP : TEXT_MARGIN_DP, 0, rtl ? TEXT_MARGIN_DP : RING_SPACE_DP, 0));

        // Character diffing from the end, as CacheChart's own counter does, so
        // "8%" → "12%" rolls the digits instead of swapping the label.
        percentText = new AnimatedTextView.AnimatedTextDrawable(false, true, true);
        percentText.setCallback(this);
        percentText.setAnimationProperties(.3f, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        percentText.setScaleProperty(.6f);
        percentText.setTypeface(AndroidUtilities.bold());
        percentText.setTextSize(dp(15));
        percentText.setGravity(Gravity.CENTER);

        trackPaint.setStyle(Paint.Style.STROKE);

        updateColors();
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /**
     * Show a state. {@code compact} is what the status line falls back to when
     * {@code full} does not fit the width; null when there is no shorter form.
     */
    void setState(int newMode, float newProgress, String full, String compact, int colorKey, boolean animated) {
        setState(newMode, newProgress, -1, full, compact, colorKey, animated);
    }

    /** @param newScore the protection percentage a finished state shows, or -1 for the tick or "!" */
    void setState(int newMode, float newProgress, int newScore, String full, String compact, int colorKey, boolean animated) {
        animated = animated && hasState;
        score = isFinal(newMode) ? Math.min(100, newScore) : -1;
        final int oldMode = mode;
        mode = newMode;
        progress = Math.max(0f, Math.min(1f, newProgress));

        if (newMode == MODE_SCANNING) {
            final boolean arcVisible = animated && lastScan > .01f;
            if (oldMode != MODE_SCANNING && !arcVisible) {
                // A new run grows its arc from the top rather than unwinding
                // the finished ring.
                sweep.force(0f);
            }
            final String percent = (int) (progress * 100f) + "%";
            if (!TextUtils.equals(percent, percentText.getText())) {
                percentText.setText(percent, arcVisible);
            }
        } else if (score >= 0) {
            // From the scan's 100% to the protection level, rolling the digits.
            final String percent = score + "%";
            if (!TextUtils.equals(percent, percentText.getText())) {
                percentText.setText(percent, animated && lastScan > .01f);
            }
        }

        if (isFinal(newMode) && newMode != ringToMode) {
            if (animated && lastComplete > .01f) {
                ringFromMode = ringToMode;
                ringToMode = newMode;
                ringColor.force(0f);
            } else {
                ringFromMode = ringToMode = newMode;
                ringColor.force(1f);
            }
        }

        statusFull = full != null ? full : "";
        statusCompact = compact;
        if (statusColorKey != colorKey) {
            statusColorKey = colorKey;
            statusView.setTextColor(color(colorKey), animated);
        }
        fitStatus(animated);

        if (!animated) {
            final boolean scanning = newMode == MODE_SCANNING;
            final boolean complete = isFinal(newMode);
            final boolean gauge = complete && score >= 0;
            sweep.force(scanning ? progress : gauge ? score / 100f : complete ? 1f : 0f);
            scanAlpha.force(scanning || gauge ? 1f : 0f);
            completeAlpha.force(complete && !gauge ? 1f : 0f);
            checkAlpha.force(newMode == MODE_CLEAN && !gauge ? 1f : 0f);
            alertAlpha.force((newMode == MODE_THREATS || newMode == MODE_NEUTRAL) && !gauge ? 1f : 0f);
            gaugeColor.force(gauge ? 1f : 0f);
            ringColor.force(1f);
            ringFromMode = ringToMode;
        }
        hasState = true;
        updateDescription();
        invalidate();
    }

    private static boolean isFinal(int mode) {
        return mode == MODE_CLEAN || mode == MODE_THREATS || mode == MODE_NEUTRAL;
    }

    /** The full status when it fits on the one line, otherwise its short form. */
    private void fitStatus(boolean animated) {
        String text = statusFull;
        // A little slack: the animated line lays each word out on its own,
        // and a line that fits only to the pixel would end in "…".
        if (statusWidth > 0 && !TextUtils.isEmpty(statusCompact)
                && statusView.getPaint().measureText(statusFull) + dp(4) > statusWidth) {
            text = statusCompact;
        }
        if (!TextUtils.equals(text, statusView.getText())) {
            statusView.setText(text, animated);
        }
    }

    private void updateDescription() {
        final StringBuilder sb = new StringBuilder();
        sb.append(titleView.getText());
        if (!TextUtils.isEmpty(statusFull)) {
            sb.append(". ").append(statusFull);
        }
        if (score >= 0) {
            sb.append(". ").append(JacStrings.get(getContext(), R.string.jac_protection_level, score));
        }
        if (mode == MODE_SCANNING) {
            // In tens, so TalkBack is not handed a new sentence every 100 ms.
            final int tens = ((int) (progress * 100f)) / 10 * 10;
            sb.append(". ").append(JacStrings.get(getContext(), R.string.jac_seccard_a11y_progress, tens));
        }
        final String description = sb.toString();
        if (!TextUtils.equals(description, getContentDescription())) {
            setContentDescription(description);
        }
    }

    // ------------------------------------------------------------------
    // View
    // ------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int available = width - dp(TEXT_MARGIN_DP) - dp(RING_SPACE_DP);
        if (available != statusWidth) {
            statusWidth = available;
            fitStatus(false);
        }
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY));
    }

    @Override
    public void updateColors() {
        titleView.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        statusView.setTextColor(color(statusColorKey));
        percentText.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == percentText || super.verifyDrawable(who);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final float size = dp(RING_DP);
        final float stroke = dp(STROKE_DP);
        final float left = LocaleController.isRTL ? dp(RING_MARGIN_DP) : getWidth() - dp(RING_MARGIN_DP) - size;
        final float top = (getHeight() - size) / 2f;
        ringBounds.set(left, top, left + size, top + size);
        final float cx = ringBounds.centerX();
        final float cy = ringBounds.centerY();
        final float radius = (size - stroke) / 2f;
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

        final boolean scanning = mode == MODE_SCANNING;
        final boolean complete = isFinal(mode);
        final boolean gauge = complete && score >= 0;
        final float scan = scanAlpha.set(scanning || gauge ? 1f : 0f);
        final float done = completeAlpha.set(complete && !gauge ? 1f : 0f);
        final float swept = sweep.set(scanning ? progress : gauge ? score / 100f : complete ? 1f : 0f);
        final float colorT = ringColor.set(1f);
        final float check = checkAlpha.set(mode == MODE_CLEAN && !gauge ? 1f : 0f);
        final float alert = alertAlpha.set((mode == MODE_THREATS || mode == MODE_NEUTRAL) && !gauge ? 1f : 0f);
        final float level = gaugeColor.set(gauge ? 1f : 0f);
        lastScan = scan;
        lastComplete = done;

        // The faint track: CacheChart's loading background.
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setColor(color(Theme.key_listSelector));
        canvas.drawCircle(cx, cy, radius, trackPaint);

        if (score >= 0) {
            lastScore = score;
        }
        if (scan > 0f && swept > 0f) {
            final int orange = color(Theme.key_color_orange);
            // A gauge fading out keeps the colour of the last score it showed
            // rather than flashing orange on its way.
            final int arc = level <= 0f || lastScore < 0 ? orange : ColorUtils.blendARGB(orange,
                    SecurityCheckup.levelColor(lastScore, resourcesProvider), level);
            arcPaint.set(lighter(arc), arc, ringBounds.top, ringBounds.bottom);
            arcPaint.paint.setStrokeWidth(stroke);
            arcPaint.paint.setAlpha((int) (0xFF * scan));
            canvas.drawArc(arcBounds, -90f, 360f * swept, false, arcPaint.paint);
        }

        if (done > 0f || check > 0f || alert > 0f) {
            ringPaint.set(
                    ColorUtils.blendARGB(topColor(ringFromMode), topColor(ringToMode), colorT),
                    ColorUtils.blendARGB(bottomColor(ringFromMode), bottomColor(ringToMode), colorT),
                    ringBounds.top, ringBounds.bottom);
            ringPaint.paint.setStrokeWidth(stroke);
            if (done > 0f) {
                ringPaint.paint.setAlpha((int) (0xFF * done));
                canvas.drawCircle(cx, cy, radius, ringPaint.paint);
            }
            if (check > 0f || alert > 0f) {
                buildPaths();
                if (check > 0f) {
                    drawGlyph(canvas, checkPath, check, cx, cy);
                }
                if (alert > 0f) {
                    drawGlyph(canvas, alertPath, alert, cx, cy);
                }
            }
        }

        if (scan > 0f) {
            percentText.setBounds((int) arcBounds.left, (int) arcBounds.top, (int) arcBounds.right, (int) arcBounds.bottom);
            percentText.setAlpha((int) (0xFF * scan));
            final float s = AndroidUtilities.lerp(.6f, 1f, scan);
            canvas.save();
            canvas.scale(s, s, cx, cy);
            percentText.draw(canvas);
            canvas.restore();
        }
    }

    private void drawGlyph(Canvas canvas, Path path, float t, float cx, float cy) {
        ringPaint.paint.setAlpha((int) (0xFF * t));
        final float s = AndroidUtilities.lerp(.5f, 1f, t);
        canvas.save();
        canvas.scale(s, s, cx, cy);
        canvas.drawPath(path, ringPaint.paint);
        canvas.restore();
    }

    /** CacheChart's tick and the scanner's "!" (see CacheChart.setCompleteStyle), in ring proportions. */
    private void buildPaths() {
        if (pathBounds.equals(ringBounds)) {
            return;
        }
        pathBounds.set(ringBounds);
        final float l = ringBounds.left;
        final float t = ringBounds.top;
        final float w = ringBounds.width();
        final float h = ringBounds.height();

        checkPath.rewind();
        checkPath.moveTo(l + w * .348f, t + h * .538f);
        checkPath.lineTo(l + w * .447f, t + h * .636f);
        checkPath.lineTo(l + w * .678f, t + h * .402f);

        // A bar, then a round-capped dot.
        alertPath.rewind();
        alertPath.moveTo(l + w * .5f, t + h * .32f);
        alertPath.lineTo(l + w * .5f, t + h * .56f);
        alertPath.moveTo(l + w * .5f, t + h * .68f);
        alertPath.lineTo(l + w * .5f, t + h * .681f);
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    /** A lighter top fading into the colour itself: the shape of upstream's green gradient. */
    private static int lighter(int color) {
        return ColorUtils.blendARGB(color, Color.WHITE, .22f);
    }

    private int topColor(int ringMode) {
        switch (ringMode) {
            case MODE_CLEAN:
                return GREEN_TOP;
            case MODE_THREATS:
                return lighter(color(Theme.key_text_RedRegular));
            default:
                return lighter(color(Theme.key_windowBackgroundWhiteGrayIcon));
        }
    }

    private int bottomColor(int ringMode) {
        switch (ringMode) {
            case MODE_CLEAN:
                return GREEN_BOTTOM;
            case MODE_THREATS:
                return color(Theme.key_text_RedRegular);
            default:
                return color(Theme.key_windowBackgroundWhiteGrayIcon);
        }
    }

    /** A round-capped stroke whose vertical gradient is rebuilt only when it changes. */
    private static final class GradientStroke {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean ready;
        private int top;
        private int bottom;
        private float y0;
        private float y1;

        GradientStroke() {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void set(int top, int bottom, float y0, float y1) {
            if (ready && this.top == top && this.bottom == bottom && this.y0 == y0 && this.y1 == y1) {
                return;
            }
            ready = true;
            this.top = top;
            this.bottom = bottom;
            this.y0 = y0;
            this.y1 = y1;
            paint.setShader(new LinearGradient(0, y0, 0, y1, top, bottom, Shader.TileMode.CLAMP));
        }
    }

    // ------------------------------------------------------------------
    // Strings
    // ------------------------------------------------------------------

    /** A plural in the language Telegram is set to; see {@link JacStrings} for why not the system one. */
    static String plural(Context context, int resId, int count) {
        try {
            return JacStrings.localized(context).getResources().getQuantityString(resId, count, count);
        } catch (Throwable t) {
            FileLog.e(t);
            return String.valueOf(count);
        }
    }

    // ------------------------------------------------------------------
    // The list item
    // ------------------------------------------------------------------

    public static class Factory extends UItem.UItemFactory<DeviceScanCard> {
        static { setup(new Factory()); }

        @Override
        public DeviceScanCard createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new DeviceScanCard(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof DeviceScanCard && item.object instanceof Controller) {
                ((Controller) item.object).bind((DeviceScanCard) view);
            }
        }

        /** The card, bound to the screen's {@link Controller}; {@code id} is the row id the screen's onClick sees. */
        public static UItem of(int id, Controller controller) {
            final UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = controller;
            return item;
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            // The card updates itself from the Controller; a list refill must
            // not rebind it into a fresh, un-animated state.
            return a.id == b.id && a.object == b.object;
        }
    }

    // ------------------------------------------------------------------
    // The screen's side
    // ------------------------------------------------------------------

    /**
     * Follows the scanner for one Settings screen and tells its card what to
     * show. Create one per screen; call {@link #resume}, {@link #pause} and
     * {@link #destroy} from the fragment's own callbacks. UI thread only.
     *
     * <h3>When it scans</h3>
     *
     * Each time the screen comes to the front (its own resume, or its tab being
     * shown) the last result is shown at once, and an incremental scan — a few
     * hundred milliseconds on a phone that has not changed — starts unless one
     * is running or one ran in the last {@link #RESCAN_AFTER_MS}. Only with the
     * user's yes to the launch-scan notice and with launch scans on: someone who
     * answered "Not now" turned automatic scans off, and opening Settings is not
     * a reason to start one behind their back. Without it the card still shows
     * the last report, and the page it opens can scan on request.
     *
     * <h3>Perceptible</h3>
     *
     * An incremental scan can end before the ring has visibly moved, which
     * reads as "nothing was checked". The ring therefore fills to 100% first and
     * the verdict lands no sooner than {@link #MIN_SCAN_SHOWN_MS} after the
     * percentage appeared — the same courtesy the scanner page gives its spinner.
     */
    public static final class Controller implements DeviceScanner.Listener {

        private static final long RESCAN_AFTER_MS = 30_000L;
        private static final long MIN_SCAN_SHOWN_MS = 1_000L;
        /** How long a full ring at 100% stays before the verdict replaces it. */
        private static final long FULL_RING_HOLD_MS = 350L;

        /** elapsedRealtime of the last scan a card started; process-wide, like the scanner. */
        private static long lastAutoScanAt;

        private Context context;
        private DeviceScanner scanner;
        private DeviceScanCard card;
        private boolean resumed;

        private boolean scanning;
        /** The run has ended; the ring shows 100% until {@link #settle} runs. */
        private boolean finishing;
        private int done;
        private int total;
        private long scanShownAt;
        private DeviceScanReport report;
        private Runnable settle;
        /** A cloud-password answer changes the percentage. */
        private final Runnable checkupListener = () -> {
            if (resumed) {
                push(true);
            }
        };

        public void resume(Context ctx) {
            if (ctx == null) {
                ctx = ApplicationLoader.applicationContext;
            }
            if (ctx == null) {
                return;
            }
            final Context app = ctx.getApplicationContext();
            context = app != null ? app : ctx;
            resumed = true;
            SecurityCheckup.addListener(checkupListener);
            SecurityCheckup.requestPassword(UserConfig.selectedAccount, false);
            if (!ScannerBootstrap.isInstalled()) {
                push(true);
                return;
            }
            if (scanner == null) {
                try {
                    scanner = DeviceScanner.getInstance(context);
                    // Listen before asking whether it runs, so a scan that
                    // finishes in between still reaches us.
                    scanner.addListener(this);
                } catch (Throwable t) {
                    FileLog.e(t);
                    scanner = null;
                }
            }
            if (scanner != null && scanner.isRunning()) {
                enterScanning();
            }
            push(true);
            // Runs at once when the report is already known; otherwise after
            // it comes off disk, which is also when "ran in the last 30 s" can
            // first be answered.
            DeviceScanUi.loadReport(context, () -> {
                if (!resumed) {
                    return;
                }
                maybeAutoScan();
                push(true);
            });
        }

        public void pause() {
            resumed = false;
            SecurityCheckup.removeListener(checkupListener);
            if (scanner != null) {
                try {
                    scanner.removeListener(this);
                } catch (Throwable ignored) {
                }
                scanner = null;
            }
            if (settle != null) {
                AndroidUtilities.cancelRunOnUIThread(settle);
                settle = null;
            }
            // Re-derived on the next resume from the scanner itself.
            scanning = false;
            finishing = false;
        }

        public void destroy() {
            pause();
            card = null;
        }

        void bind(DeviceScanCard view) {
            if (view == null) {
                return;
            }
            if (context == null) {
                final Context app = view.getContext().getApplicationContext();
                context = app != null ? app : view.getContext();
            }
            card = view;
            // A card that is already showing something animates to the
            // current state; a freshly created one starts in it.
            push(view.hasState);
        }

        private void maybeAutoScan() {
            if (!resumed || scanning || scanner == null || context == null || scanner.isRunning()) {
                return;
            }
            if (!ScannerBootstrap.isInstalled() || !autoScanAllowed(context)) {
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            if (lastAutoScanAt != 0 && now - lastAutoScanAt < RESCAN_AFTER_MS) {
                return;
            }
            report = newer(report, DeviceScanUi.peekReport());
            if (report != null) {
                final long age = System.currentTimeMillis() - report.finishedAt;
                if (age >= 0 && age < RESCAN_AFTER_MS) {
                    return;
                }
            }
            lastAutoScanAt = now;
            enterScanning();
            try {
                scanner.start(DeviceScanner.TRIGGER_MANUAL, null);
            } catch (Throwable t) {
                FileLog.e(t);
                scanning = false;
            }
        }

        /** The user's yes to the launch-scan notice, and launch scans still on. */
        private static boolean autoScanAllowed(Context c) {
            try {
                return HumogramConfig.isDeviceScanOnLaunch(c) && HumogramConfig.getDeviceScanConsent(c) == 1;
            } catch (Throwable t) {
                return false;
            }
        }

        private void enterScanning() {
            if (settle != null) {
                AndroidUtilities.cancelRunOnUIThread(settle);
                settle = null;
            }
            finishing = false;
            if (!scanning) {
                scanning = true;
                done = 0;
                total = 0;
                scanShownAt = SystemClock.elapsedRealtime();
            }
        }

        @Override
        public void onProgress(int done, int total, String currentLabel) {
            if (!resumed) {
                return;
            }
            if (!scanning || finishing) {
                // A run started elsewhere (the launch hook, the scanner page),
                // or a new one right after the last.
                enterScanning();
            }
            this.done = done;
            this.total = total;
            push(true);
        }

        @Override
        public void onFinished(DeviceScanReport finished) {
            if (!resumed) {
                return;
            }
            if (finished != null) {
                DeviceScanUi.remember(finished);
                report = newer(report, finished);
            }
            if (!scanning) {
                push(true);
                return;
            }
            finishing = true;
            push(true);
            final long shown = SystemClock.elapsedRealtime() - scanShownAt;
            final long delay = Math.max(MIN_SCAN_SHOWN_MS - shown, FULL_RING_HOLD_MS);
            if (settle != null) {
                AndroidUtilities.cancelRunOnUIThread(settle);
            }
            settle = () -> {
                settle = null;
                scanning = false;
                finishing = false;
                push(true);
            };
            AndroidUtilities.runOnUIThread(settle, delay);
        }

        private void push(boolean animated) {
            final DeviceScanCard view = card;
            final Context c = context;
            if (view == null || c == null) {
                return;
            }
            final int gray = Theme.key_windowBackgroundWhiteGrayText;
            final int account = UserConfig.selectedAccount;
            if (!ScannerBootstrap.isInstalled()) {
                view.setState(MODE_NEUTRAL, 1f, SecurityCheckup.score(c, null, account),
                        JacStrings.get(c, R.string.jac_seccard_unavailable), null, gray, animated);
                return;
            }
            if (scanning) {
                final float fraction = finishing ? 1f
                        : total > 0 ? Math.min(done, total) / (float) total : 0f;
                view.setState(MODE_SCANNING, fraction, JacStrings.get(c, R.string.jac_seccard_scanning), null, gray, animated);
                return;
            }
            final DeviceScanReport r = report = newer(report, DeviceScanUi.peekReport());
            if (r == null) {
                if (!DeviceScanUi.isReportLoaded()) {
                    // The last report is still coming off disk: a bare track,
                    // for the few milliseconds that takes.
                    view.setState(MODE_EMPTY, 0f, "", null, gray, animated);
                } else {
                    view.setState(MODE_NEUTRAL, 1f, SecurityCheckup.score(c, null, account),
                            JacStrings.get(c, R.string.jac_seccard_never), null, gray, animated);
                }
                return;
            }
            final int score = SecurityCheckup.score(c, r, account);
            if (DeviceScanUi.isEmptyFailure(r)) {
                // Checked nothing, so it may not say "no threats".
                view.setState(MODE_NEUTRAL, 1f, score, JacStrings.get(c, R.string.jac_seccard_failed), null, gray, animated);
                return;
            }
            int threats = 0;
            try {
                threats = r.threatCount(c);
            } catch (Throwable t) {
                FileLog.e(t);
            }
            if (threats > 0) {
                view.setState(MODE_THREATS, 1f, score, plural(c, R.plurals.jac_seccard_threats, threats), null,
                        Theme.key_text_RedRegular, animated);
                return;
            }
            final String clean = JacStrings.get(c, R.string.jac_seccard_clean);
            final String full = r.appsChecked > 0
                    ? clean + " · " + plural(c, R.plurals.jac_seccard_apps_checked, r.appsChecked)
                    : clean;
            view.setState(MODE_CLEAN, 1f, score, full, clean, gray, animated);
        }

        private static DeviceScanReport newer(DeviceScanReport a, DeviceScanReport b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return b.finishedAt >= a.finishedAt ? b : a;
        }
    }
}
