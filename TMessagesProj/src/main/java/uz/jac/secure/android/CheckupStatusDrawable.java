package uz.jac.secure.android;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;

/**
 * The verdict at the end of a device-check row: a green disc with a tick, or
 * an orange (attention) or red (risk) disc with "!".
 *
 * <p>It goes into the value slot of an upstream {@code TextCell}
 * ({@code setTextAndValueDrawable}), so the row itself — height, insets,
 * ripple, divider — stays Telegram's. Colours come from {@link Theme} at draw
 * time, so a theme switch repaints it; the glyph is white, as on Telegram's
 * own coloured badges. The tick and the "!" use the same proportions as the
 * scanner's ring ({@code CacheChart.setCompleteStyle}).
 */
final class CheckupStatusDrawable extends Drawable {

    private static final int SIZE_DP = 22;

    private final int severity;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect pathFor = new Rect();
    private int alpha = 0xFF;

    CheckupStatusDrawable(int severity) {
        this.severity = severity;
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        glyph.setColor(Color.WHITE);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        final float size = Math.min(b.width(), b.height());
        if (size <= 0) {
            return;
        }
        final float cx = b.exactCenterX();
        final float cy = b.exactCenterY();
        fill.setColor(Theme.getColor(colorKey()));
        fill.setAlpha(alpha);
        canvas.drawCircle(cx, cy, size / 2f, fill);

        if (!pathFor.equals(b)) {
            pathFor.set(b);
            final float l = cx - size / 2f;
            final float t = cy - size / 2f;
            path.rewind();
            if (severity == SecurityCheckup.OK) {
                path.moveTo(l + size * .30f, t + size * .52f);
                path.lineTo(l + size * .44f, t + size * .66f);
                path.lineTo(l + size * .71f, t + size * .38f);
            } else {
                path.moveTo(l + size * .5f, t + size * .27f);
                path.lineTo(l + size * .5f, t + size * .57f);
                path.moveTo(l + size * .5f, t + size * .73f);
                path.lineTo(l + size * .5f, t + size * .731f);
            }
        }
        glyph.setStrokeWidth(size * .11f);
        glyph.setAlpha(alpha);
        canvas.drawPath(path, glyph);
    }

    private int colorKey() {
        switch (severity) {
            case SecurityCheckup.OK:
                return Theme.key_color_green;
            case SecurityCheckup.DANGER:
                return Theme.key_text_RedRegular;
            default:
                return Theme.key_color_orange;
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return dp(SIZE_DP);
    }

    @Override
    public int getIntrinsicHeight() {
        return dp(SIZE_DP);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
