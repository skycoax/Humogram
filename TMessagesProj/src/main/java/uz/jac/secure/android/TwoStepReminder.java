package uz.jac.secure.android;

import android.app.Activity;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.TwoStepVerificationSetupActivity;

/**
 * At every launch, for as long as the account has no cloud password: a dialog
 * that says so and opens Telegram's own setup.
 *
 * <pre>TwoStepReminder.maybeShow(this);   // DialogsActivity.onResume</pre>
 *
 * <h3>Why a dialog, every launch</h3>
 *
 * Almost every stolen Telegram account in this app's audience is taken the
 * same way: a login code talked or phished out of the owner. A cloud password
 * is the one thing that stops that, and the owner asked for it to be raised
 * every time the app opens until it is set. "Later" closes it for this launch;
 * the switch on the Humogram page turns it off for good, for the person who
 * has decided.
 *
 * <h3>When</h3>
 *
 * Once per process per account, a moment after the chat list appears, and
 * only once it can be seen: not behind the passcode screen, not over another
 * dialog (the virus scanner's one-time notice comes first and a fragment shows
 * one dialog at a time — the second would close the first), not when the user
 * has already moved on. Any of those and it waits for the next resume. The
 * answer comes from {@link SecurityCheckup}, the request the Humogram page
 * already makes, so it is one request per account and its password object is
 * the one the setup wizard needs.
 */
public final class TwoStepReminder {

    /** Behind the scanner's own launch delay, so a first launch shows its notice first. */
    private static final long DELAY_MS = 2500;

    private static final long RETRY_MS = 2000;
    private static final int MAX_ATTEMPTS = 60;

    private static final boolean[] shown = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static final int[] attempts = new int[UserConfig.MAX_ACCOUNT_COUNT];
    private static final boolean[] waiting = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private TwoStepReminder() {
    }

    public static void maybeShow(BaseFragment fragment) {
        try {
            if (fragment == null || fragment.getContext() == null || fragment.isInPreviewMode()) {
                return;
            }
            final int account = fragment.getCurrentAccount();
            if (!valid(account) || shown[account] || waiting[account]) {
                return;
            }
            if (!HumogramConfig.isTwoStepReminder(fragment.getContext())) {
                return;
            }
            waiting[account] = true;
            attempts[account] = 0;
            AndroidUtilities.runOnUIThread(() -> {
                waiting[account] = false;
                ask(fragment, account);
            }, DELAY_MS);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void ask(BaseFragment fragment, int account) {
        if (shown[account]) {
            return;
        }
        if (!canShow(fragment)) {
            retryLater(fragment, account);
            return;
        }
        final TL_account.Password known = SecurityCheckup.getPassword(account);
        if (known != null) {
            show(fragment, account, known);
            return;
        }
        // Not asked yet this launch: ask, and decide when Telegram answers.
        final Runnable[] listener = new Runnable[1];
        listener[0] = () -> {
            final TL_account.Password password = SecurityCheckup.getPassword(account);
            if (password != null || SecurityCheckup.isPasswordUnknown(account)) {
                SecurityCheckup.removeListener(listener[0]);
                if (password != null) {
                    show(fragment, account, password);
                }
            }
        };
        SecurityCheckup.addListener(listener[0]);
        SecurityCheckup.requestPassword(account, false);
    }

    private static void show(BaseFragment fragment, int account, TL_account.Password password) {
        try {
            if (shown[account]) {
                return;
            }
            if (password.has_password) {
                // Set, so nothing to say this launch.
                shown[account] = true;
                return;
            }
            if (!canShow(fragment)) {
                retryLater(fragment, account);
                return;
            }
            final Activity activity = fragment.getParentActivity();
            // Mid-setup (the password is waiting on an email code): upstream's
            // own choice in Privacy settings is the code screen, not a fresh start.
            final boolean emailPending = !TextUtils.isEmpty(password.email_unconfirmed_pattern);
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTopAnimation(R.raw.passcode_lock, 72, false, Theme.getColor(Theme.key_dialogTopBackground))
                    .setTitle(JacStrings.get(activity, R.string.jac_2fa_reminder_title))
                    .setMessage(JacStrings.get(activity, emailPending
                            ? R.string.jac_2fa_reminder_email_pending : R.string.jac_2fa_reminder_text))
                    .setPositiveButton(JacStrings.get(activity, emailPending
                            ? R.string.jac_2fa_reminder_continue : R.string.jac_checkup_set_up), (d, which) ->
                            fragment.presentFragment(new TwoStepVerificationSetupActivity(emailPending
                                    ? TwoStepVerificationSetupActivity.TYPE_EMAIL_CONFIRM
                                    : TwoStepVerificationSetupActivity.TYPE_INTRO, password)))
                    .setNegativeButton(JacStrings.get(activity, R.string.jac_2fa_reminder_later), null)
                    .create();
            if (fragment.showDialog(dialog) != null) {
                shown[account] = true;
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /**
     * Try again shortly while the chat list is still there: the scanner's
     * notice being closed, or the passcode entered, does not resume the list,
     * so waiting for a resume alone could skip a whole launch. Bounded, and a
     * resume of the list starts its own try.
     */
    private static void retryLater(BaseFragment fragment, int account) {
        if (attempts[account] >= MAX_ATTEMPTS || fragment == null || fragment.getParentActivity() == null
                || fragment.isRemovingFromStack()) {
            return;
        }
        attempts[account]++;
        AndroidUtilities.runOnUIThread(() -> ask(fragment, account), RETRY_MS);
    }

    /** The chat list is in front, unlocked, and not already showing a dialog. */
    private static boolean canShow(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null || fragment.isPaused()
                || fragment.getVisibleDialog() != null) {
            return false;
        }
        if (!UserConfig.getInstance(fragment.getCurrentAccount()).isClientActivated()) {
            return false;
        }
        final String hash = SharedConfig.passcodeHash;
        return !SharedConfig.isWaitingForPasscodeEnter
                && !(hash != null && hash.length() > 0 && SharedConfig.appLocked);
    }

    private static boolean valid(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
    }
}
