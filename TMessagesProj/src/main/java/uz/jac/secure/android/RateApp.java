package uz.jac.secure.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.browser.Browser;

/**
 * "Rate us": Google Play's own rating sheet over the app, with the store
 * listing as the fallback.
 *
 * <p>The sheet is Play's In-App Review flow — the system bottom sheet with the
 * five stars, drawn by Play rather than by us, so it is the official dialog and
 * the rating lands on the listing without the user leaving the app. Both the
 * row under the cyber-safety channels in Settings ({@link RecommendedPeers})
 * and the chat-list hint come here.
 *
 * <h3>Why there is a fallback</h3>
 *
 * Play decides whether the sheet appears and never says which way it went: it
 * is rationed per user, not shown again to someone who has already rated, and
 * not shown at all to an install that did not come from Play. Behind a button
 * the user pressed, "nothing happened" is a broken button, so a flow that comes
 * back quicker than anyone could have seen the sheet is taken to mean it was
 * not shown, and the store listing opens instead.
 */
public final class RateApp {

    /**
     * The Play listing. Fixed rather than read from the context: debug builds
     * carry a ".beta" suffix the store has never heard of.
     */
    private static final String PLAY_PACKAGE = "uz.humogram.app";

    /** A finished flow quicker than this was not seen by anyone. */
    private static final long NOT_SHOWN_MS = 1_000L;

    private RateApp() {
    }

    /** UI thread. Shows Play's rating sheet, or opens the listing when Play will not. */
    public static void launch(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final ReviewManager manager;
        try {
            manager = ReviewManagerFactory.create(activity);
        } catch (Throwable t) {
            FileLog.e(t);
            openStore(activity);
            return;
        }
        manager.requestReviewFlow().addOnCompleteListener(request -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            if (!request.isSuccessful()) {
                // No Play services, no Play account, or not a Play install.
                openStore(activity);
                return;
            }
            final ReviewInfo info = request.getResult();
            final long started = SystemClock.elapsedRealtime();
            manager.launchReviewFlow(activity, info).addOnCompleteListener(flow -> {
                if (SystemClock.elapsedRealtime() - started < NOT_SHOWN_MS
                        && !activity.isFinishing() && !activity.isDestroyed()) {
                    openStore(activity);
                }
            });
        });
    }

    private static void openStore(Activity activity) {
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PLAY_PACKAGE));
            intent.setPackage("com.android.vending");
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // No Play Store app: the same listing on the web.
            Browser.openUrl(activity, "https://play.google.com/store/apps/details?id=" + PLAY_PACKAGE);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
