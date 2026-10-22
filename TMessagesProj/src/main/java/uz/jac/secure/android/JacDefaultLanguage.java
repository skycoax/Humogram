package uz.jac.secure.android;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

/**
 * Makes the interface Uzbek on the very first launch — the owner's call
 * (2026-10-16): the people this app is for read Uzbek, and asking them to find
 * the language picker first is a barrier the product should not have.
 *
 * <h3>Why this needs more than a one-liner</h3>
 *
 * Uzbek is not one of Telegram's built-in languages ({@code en}, {@code ru},
 * {@code uk}, …); it is a remote language pack, fetched from the server and
 * then cached. So on the first launch the Uzbek {@code LocaleInfo} is not in
 * {@link LocaleController}'s dictionary yet. This waits for the remote list to
 * arrive ({@link NotificationCenter#suggestedLangpack}), then applies Uzbek —
 * which downloads the strings and switches the UI. From the second launch on,
 * the pack is cached and it applies immediately.
 *
 * <h3>Once, and never against the user</h3>
 *
 * The persisted flag is set only after Uzbek has actually been applied, so a
 * first launch that is killed before the download finishes retries next time
 * rather than leaving the user on English for good. After it has run once the
 * language is the user's to change in Settings, and this never touches it
 * again.
 */
public final class JacDefaultLanguage {

    private static final String TARGET = "uz";
    /** One attempt (and one observer) per process. */
    private static boolean attemptedThisProcess;

    private JacDefaultLanguage() {
    }

    /** UI thread. Called once from {@link ApplicationLoader#onCreate()}. */
    public static void ensureFirstLaunch() {
        try {
            if (attemptedThisProcess) {
                return;
            }
            final Context context = ApplicationLoader.applicationContext;
            if (context == null || HumogramConfig.isDefaultLanguageDone(context)) {
                attemptedThisProcess = true;
                return;
            }
            attemptedThisProcess = true;

            final int account = UserConfig.selectedAccount;
            final LocaleController lc = LocaleController.getInstance();
            if (apply(context, lc, account)) {
                return;
            }
            // Not in the dictionary yet: wait for the remote language list, then
            // apply once and stop listening.
            final NotificationCenter.NotificationCenterDelegate[] observer =
                    new NotificationCenter.NotificationCenterDelegate[1];
            observer[0] = (id, acc, args) -> {
                if (apply(ApplicationLoader.applicationContext, LocaleController.getInstance(),
                        UserConfig.selectedAccount)) {
                    NotificationCenter.getGlobalInstance()
                            .removeObserver(observer[0], NotificationCenter.suggestedLangpack);
                }
            };
            NotificationCenter.getGlobalInstance()
                    .addObserver(observer[0], NotificationCenter.suggestedLangpack);
            lc.loadRemoteLanguages(account);
        } catch (Throwable ignored) {
            // The language default must never be the thing that breaks launch.
        }
    }

    /**
     * Apply Uzbek if it is available now.
     *
     * @return true when nothing more needs doing — either Uzbek is applied, or
     *         it is already the current language. False means "not in the
     *         dictionary yet; keep waiting."
     */
    private static boolean apply(Context context, LocaleController lc, int account) {
        try {
            final LocaleController.LocaleInfo current = lc.getCurrentLocaleInfo();
            if (current != null && TARGET.equals(current.shortName)) {
                markDone(context);
                return true;
            }
            final LocaleController.LocaleInfo uz = lc.getLanguageFromDict(TARGET);
            if (uz == null) {
                return false;
            }
            lc.applyLanguage(uz, true, false, account);
            markDone(context);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void markDone(Context context) {
        if (context != null) {
            HumogramConfig.setDefaultLanguageDone(context);
        }
    }
}
