package uz.jac.secure.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.TwoStepVerificationActivity;
import org.telegram.ui.TwoStepVerificationSetupActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * The device check, as rows on {@link HumogramSettingsActivity} under the
 * virus scanner: screen lock, root, USB debugging, security updates and the
 * account's cloud password, each ending in a green tick or an "!"
 * ({@link CheckupStatusDrawable}). Tapping a row says what the check means and,
 * when something is wrong, offers the one thing that fixes it — the system
 * settings screen, or Telegram's own cloud-password setup.
 *
 * <p>Like {@link DeviceScanSection}, a section rather than a page: the host
 * forwards {@link #fillItems}, {@link #onClick}, {@link #onResume} and
 * {@link #onDestroy}. Everything it shows also feeds the protection
 * percentage ({@link SecurityCheckup#score}).
 *
 * <h3>Why the checks re-run on resume</h3>
 *
 * Every fix happens somewhere else — in system settings or in the 2FA wizard —
 * and the user comes straight back afterwards. A row that still said "risk"
 * after the thing was fixed would teach people the check does not know what it
 * is talking about, so the local checks run on every fill and the cloud
 * password is asked again after a trip to its setup.
 */
final class SecurityCheckupSection {

    /** Row ids: above the scanner section's (1 00x) and the host's own. */
    private static final int ROW_BASE = 2_000;

    private static final int[] LOCAL_ORDER = {
            SecurityCheckup.CHECK_SCREEN_LOCK,
            SecurityCheckup.CHECK_ROOT,
            SecurityCheckup.CHECK_ADB,
            SecurityCheckup.CHECK_PATCH,
    };

    private final BaseFragment fragment;
    private final DeviceScanSection.Host host;
    private final Runnable passwordListener;

    private List<SecurityCheckup.Finding> findings;
    /** Set when the user left for the cloud-password setup: ask again on return. */
    private boolean recheckPassword;
    private boolean destroyed;

    SecurityCheckupSection(BaseFragment fragment, DeviceScanSection.Host host) {
        this.fragment = fragment;
        this.host = host;
        passwordListener = () -> {
            if (!destroyed) {
                host.refresh(true);
            }
        };
        SecurityCheckup.addListener(passwordListener);
        SecurityCheckup.requestPassword(fragment.getCurrentAccount(), false);
    }

    void onResume() {
        if (destroyed) {
            return;
        }
        SecurityCheckup.requestPassword(fragment.getCurrentAccount(), recheckPassword);
        recheckPassword = false;
    }

    void onDestroy() {
        destroyed = true;
        SecurityCheckup.removeListener(passwordListener);
    }

    void fillItems(ArrayList<UItem> items) {
        final Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        findings = SecurityCheckup.runLocal(context);
        items.add(UItem.asHeader(JacStrings.get(context, R.string.jac_checkup)));
        for (int i = 0; i < LOCAL_ORDER.length; i++) {
            final int id = LOCAL_ORDER[i];
            items.add(statusRow(context, id, severityOf(id)));
        }
        final int account = fragment.getCurrentAccount();
        final TL_account.Password password = SecurityCheckup.getPassword(account);
        if (password != null) {
            items.add(statusRow(context, SecurityCheckup.CHECK_2FA,
                    password.has_password ? SecurityCheckup.OK : SecurityCheckup.WARN));
        } else {
            // Still asking, or Telegram could not be reached: words, not a
            // verdict — a guess here would be either a false alarm or a false
            // all-clear.
            items.add(UItem.asButton(ROW_BASE + SecurityCheckup.CHECK_2FA,
                    JacStrings.get(context, titleFor(SecurityCheckup.CHECK_2FA)),
                    SecurityCheckup.isPasswordUnknown(account)
                            ? JacStrings.get(context, R.string.jac_checkup_unknown) : "…"));
        }
        items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_checkup_note)));
    }

    private UItem statusRow(Context context, int id, int severity) {
        final CharSequence title = JacStrings.get(context, titleFor(id));
        // The cloud password is the last row of the card.
        final boolean divider = id != SecurityCheckup.CHECK_2FA;
        final CheckupStatusDrawable icon = new CheckupStatusDrawable(severity);
        final UItem item = UItem.asButton(ROW_BASE + id, title);
        // intValue is part of UItem's equality: a changed verdict is a new
        // row to the diff, so the icon cannot be left over from the old one.
        item.intValue = severity;
        return item.onBind(view -> {
            if (view instanceof TextCell) {
                ((TextCell) view).setTextAndValueDrawable(title, icon, divider);
                view.setContentDescription(title + ", " + JacStrings.get(view.getContext(), statusWord(severity)));
            }
        });
    }

    private int severityOf(int id) {
        if (findings != null) {
            for (SecurityCheckup.Finding finding : findings) {
                if (finding.id == id) {
                    return finding.severity;
                }
            }
        }
        return SecurityCheckup.OK;
    }

    /** True when the row was one of the device check's and has been handled. */
    boolean onClick(UItem item) {
        final int id = item.id - ROW_BASE;
        if (id < SecurityCheckup.CHECK_SCREEN_LOCK || id > SecurityCheckup.CHECK_2FA) {
            return false;
        }
        final Activity activity = fragment.getParentActivity();
        final Context context = fragment.getContext();
        if (activity == null || context == null) {
            return true;
        }
        if (id == SecurityCheckup.CHECK_2FA) {
            onPasswordClick(activity, context);
            return true;
        }
        final int severity = severityOf(id);
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(JacStrings.get(context, titleFor(id)))
                .setMessage(JacStrings.get(context, adviceFor(id, severity)));
        final String action = SecurityCheckup.settingsActionFor(id);
        if (severity != SecurityCheckup.OK && action != null) {
            builder.setPositiveButton(JacStrings.get(context, R.string.jac_checkup_open_settings), (d, which) -> {
                try {
                    activity.startActivity(new Intent(action));
                } catch (Throwable ignored) {
                    // No such settings screen on this device; the advice stands.
                }
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        } else {
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        }
        fragment.showDialog(builder.create());
        return true;
    }

    private void onPasswordClick(Activity activity, Context context) {
        final int account = fragment.getCurrentAccount();
        final TL_account.Password password = SecurityCheckup.getPassword(account);
        if (password == null) {
            // "Couldn't check" (or still asking): a tap asks again.
            SecurityCheckup.requestPassword(account, true);
            host.refresh(true);
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(JacStrings.get(context, R.string.jac_checkup_2fa))
                .setMessage(JacStrings.get(context, password.has_password
                        ? R.string.jac_checkup_2fa_ok : R.string.jac_checkup_2fa_advice));
        if (!password.has_password) {
            builder.setPositiveButton(JacStrings.get(context, R.string.jac_checkup_set_up), (d, which) -> {
                recheckPassword = true;
                // Upstream's own choice in Privacy settings: the intro wizard
                // for a fresh account, the email-code screen for one already
                // mid-setup. The password fetched for the row is handed over,
                // so the wizard does not open on a spinner.
                final int type = TextUtils.isEmpty(password.email_unconfirmed_pattern)
                        ? TwoStepVerificationSetupActivity.TYPE_INTRO
                        : TwoStepVerificationSetupActivity.TYPE_EMAIL_CONFIRM;
                fragment.presentFragment(new TwoStepVerificationSetupActivity(type, password));
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        } else {
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            builder.setNegativeButton(JacStrings.get(context, R.string.jac_checkup_open_settings), (d, which) -> {
                recheckPassword = true;
                final TwoStepVerificationActivity settings = new TwoStepVerificationActivity();
                settings.setPassword(password);
                fragment.presentFragment(settings);
            });
        }
        fragment.showDialog(builder.create());
    }

    private static int titleFor(int id) {
        switch (id) {
            case SecurityCheckup.CHECK_SCREEN_LOCK:
                return R.string.jac_checkup_screen_lock;
            case SecurityCheckup.CHECK_ROOT:
                return R.string.jac_checkup_root;
            case SecurityCheckup.CHECK_ADB:
                return R.string.jac_checkup_adb;
            case SecurityCheckup.CHECK_PATCH:
                return R.string.jac_checkup_patch;
            default:
                return R.string.jac_checkup_2fa;
        }
    }

    /** What the check means: the fix when it failed, the reassurance when it passed. */
    private static int adviceFor(int id, int severity) {
        final boolean ok = severity == SecurityCheckup.OK;
        switch (id) {
            case SecurityCheckup.CHECK_SCREEN_LOCK:
                return ok ? R.string.jac_checkup_screen_lock_ok : R.string.jac_checkup_screen_lock_advice;
            case SecurityCheckup.CHECK_ROOT:
                return ok ? R.string.jac_checkup_root_ok : R.string.jac_checkup_root_advice;
            case SecurityCheckup.CHECK_ADB:
                return ok ? R.string.jac_checkup_adb_ok : R.string.jac_checkup_adb_advice;
            case SecurityCheckup.CHECK_PATCH:
                return ok ? R.string.jac_checkup_patch_ok : R.string.jac_checkup_patch_advice;
            default:
                return R.string.jac_checkup_2fa_advice;
        }
    }

    private static int statusWord(int severity) {
        switch (severity) {
            case SecurityCheckup.OK:
                return R.string.jac_checkup_ok;
            case SecurityCheckup.DANGER:
                return R.string.jac_checkup_risk;
            default:
                return R.string.jac_checkup_warn;
        }
    }
}
