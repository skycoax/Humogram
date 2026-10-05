package uz.jac.secure.android;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * One {@link JacIcons} glyph as a {@link Drawable}, inked with a {@link Theme}
 * colour key.
 *
 * <p>The device scanner needs the verdict glyphs in two places a Canvas call
 * cannot reach: the top band of an upstream {@code AlertDialog} (which takes a
 * Drawable) and the icon slot of an upstream {@code TextCell} while an app's
 * real icon is still loading. {@code QuarantineDialogs} has a drawable like
 * this, but it is private to that class and paints fixed white — this one takes
 * a theme key instead, per the house rule, and resolves it on every draw so a
 * theme switch while the page is open recolours it without a rebind.
 *
 * <p>The colour is read at draw time, not captured at construction: a drawable
 * that remembered the day-theme grey would keep painting it at night.
 */
final class DeviceScanGlyphDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final JacIcons.Glyph glyph;
    private final int colorKey;
    private final int sizePx;
    private int alpha = 255;

    /**
     * @param glyph    what to draw
     * @param sizeDp   intrinsic size; upstream cells and dialogs lay the
     *                 drawable out at exactly this, so it is the rendered size
     * @param colorKey a {@code Theme.key_*} colour for the ink
     */
    DeviceScanGlyphDrawable(JacIcons.Glyph glyph, int sizeDp, int colorKey) {
        this.glyph = glyph;
        this.colorKey = colorKey;
        this.sizePx = AndroidUtilities.dp(sizeDp);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            box.set(0, 0, sizePx, sizePx);
        } else {
            box.set(bounds);
        }
        final int color = Theme.getColor(colorKey);
        paint.setColor(color);
        if (alpha != 255) {
            paint.setAlpha(Color.alpha(color) * alpha / 255);
        }
        JacIcons.draw(canvas, glyph, box, paint);
    }

    @Override
    public int getIntrinsicWidth() {
        return sizePx;
    }

    @Override
    public int getIntrinsicHeight() {
        return sizePx;
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
