package uz.jac.secure.android;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Process;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the real launcher icons of the apps the scanner lists, off the main
 * thread, pre-sized for an upstream {@code TextCell}.
 *
 * <h3>Why not in the bind</h3>
 *
 * {@code PackageManager.getApplicationIcon} is a binder call plus a resource
 * load from another package's APK — a few milliseconds each, sometimes tens.
 * Called from {@code onBindViewHolder} it would stutter every fling of a list
 * that can hold a hundred apps. So the page asks for the icons once per report,
 * this class fetches them on its own background thread, and the page keeps the
 * results for as long as it lives. Until an icon arrives the row shows a
 * neutral glyph, never an empty gap that would shift the text sideways.
 *
 * <h3>Why pre-sized</h3>
 *
 * {@code TextCell} lays its image out at the drawable's intrinsic size, and a
 * launcher icon is typically 48 dp or more — it would overlap the title. Each
 * icon is therefore rendered once into a bitmap of the size the row wants
 * (adaptive icons draw their mask shape at any bounds), which also means the
 * list never scales another app's full-resolution drawable on the draw path.
 *
 * <p>Results arrive in small batches so the first screen of rows fills in
 * quickly even when the list is long.
 */
final class DeviceScanIcons {

    interface Callback {
        /** UI thread. The map holds only icons that loaded; failures keep the placeholder. */
        void onLoaded(Map<String, Drawable> icons);
    }

    /** Icons delivered per UI update: enough to fill a screen, few enough to show early. */
    private static final int BATCH = 12;

    /**
     * One thread, shared by every page instance: icon loading is never urgent
     * enough to be worth two threads racing the same PackageManager.
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jac-devscan-icons");
        thread.setDaemon(true);
        return thread;
    });

    private DeviceScanIcons() {
    }

    static void load(Context context, List<String> packages, int sizePx, Callback callback) {
        if (context == null || packages == null || packages.isEmpty() || callback == null) {
            return;
        }
        // The application context: a page closed mid-load must not be kept
        // alive by the loader, and resources resolve the same either way.
        final Context app = context.getApplicationContext();
        final List<String> wanted = new ArrayList<>(packages);
        EXECUTOR.execute(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {
            }
            final PackageManager pm = app.getPackageManager();
            final Resources res = app.getResources();
            HashMap<String, Drawable> batch = new HashMap<>();
            for (String pkg : wanted) {
                try {
                    batch.put(pkg, render(res, pm.getApplicationIcon(pkg), sizePx));
                } catch (Throwable ignored) {
                    // Uninstalled since the scan, or an icon that will not
                    // draw: the row keeps its placeholder, which is honest.
                }
                if (batch.size() >= BATCH) {
                    deliver(batch, callback);
                    batch = new HashMap<>();
                }
            }
            if (!batch.isEmpty()) {
                deliver(batch, callback);
            }
        });
    }

    private static void deliver(Map<String, Drawable> batch, Callback callback) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                callback.onLoaded(batch);
            } catch (Throwable ignored) {
                // A page torn down between the post and the run must not crash.
            }
        });
    }

    /** Draw {@code source} once into a square bitmap of {@code size} pixels. */
    private static Drawable render(Resources res, Drawable source, int size) {
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        // Same density as the resources, so BitmapDrawable reports exactly
        // `size` as its intrinsic size instead of rescaling it.
        bitmap.setDensity(res.getDisplayMetrics().densityDpi);
        final Canvas canvas = new Canvas(bitmap);
        final Rect previous = source.copyBounds();
        source.setBounds(0, 0, size, size);
        source.draw(canvas);
        source.setBounds(previous);
        return new BitmapDrawable(res, bitmap);
    }
}
