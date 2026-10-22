package uz.jac.secure.android;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;

/**
 * The dialogs that stand between a quarantined file and being opened.
 *
 * <h3>Built from Telegram's own dialog</h3>
 *
 * These used to be a hand-rolled {@code android.app.Dialog} with its own card,
 * corner radius, button stack and checkbox. Per the house rule they are now
 * upstream {@link AlertDialog}s: the same component every confirmation in the
 * app uses, so they inherit its typography, its ripple, its theming and its
 * button behaviour, and they read as part of the app rather than a thing
 * pasted over it. The only fork-drawn pixels left are the verdict glyph in the
 * top band — {@link JacIcons} geometry on the fixed verdict red, which is
 * deliberately outside the theme engine so no downloaded theme can dress a
 * warning up as something friendly.
 *
 * <h3>Why two steps, and why they do not look alike</h3>
 *
 * One confirmation does not work. Dismissing a dialog is a reflex — same
 * position, same motion, several times a day — and the tap lands before the
 * text is read. A second dialog only helps if it breaks that reflex:
 *
 * <ul>
 *   <li><b>Different words.</b> Step one describes the file. Step two describes
 *       what happens to the person: someone reads their SMS codes and takes
 *       money out of their account.</li>
 *   <li><b>Different gesture.</b> Step two will not proceed on a tap at all.
 *       The dangerous button is inert until the checkbox is ticked.</li>
 *   <li><b>The numbering is honest.</b> "Step 1 of 2" says, at the moment of
 *       deciding, that this is not the last screen — and promises there is no
 *       third one.</li>
 * </ul>
 *
 * <h3>Contract</h3>
 *
 * {@code onReleaseConfirmed} runs only after both steps and the checkbox. The
 * caller releases the file via {@link ScanGate#releaseAfterOverride}, which
 * independently re-checks that both confirmations were given — this class is
 * the interface, not the enforcement.
 */
public final class QuarantineDialogs {

    /** Seconds both buttons stay inert on the virus warning. */
    private static final int COUNTDOWN_SECONDS = 3;

    public interface ReleaseListener {
        void onReleaseConfirmed(File file);
    }

    private QuarantineDialogs() {
    }

    /**
     * Step 1 of 2 — what the file is.
     *
     * @param explanation the localised, one-sentence reason from {@link ScanUi}
     */
    public static void showBlocked(Activity activity, File file, String explanation, ReleaseListener listener) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTopImage(new GlyphDrawable(JacIcons.Glyph.BLOCK), JacTheme.danger(activity))
                .setTitle(JacStrings.get(activity, R.string.jac_quarantine_title))
                .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 1, 2))
                .setMessage(JacStrings.get(activity, R.string.jac_quarantine_body,
                        explanation != null ? explanation : ""))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_quarantine_keep_safe), null)
                .setNegativeButton(JacStrings.get(activity, R.string.jac_quarantine_open_anyway),
                        (d, which) -> showConfirm(activity, file, listener))
                .create();
        // The spoken warning belongs to every screen that stands in front of a
        // dangerous file, not only the tap-interception one — this path used
        // to be the silent way in.
        warningShowing = true;
        dialog.setOnDismissListener(d -> onWarningDismissed());
        JacAlarm.voice(activity, R.raw.jac_voice_file);
        dialog.show();
        // The way through is legal but must not be the loud thing on the
        // screen: the safe button keeps the accent, the dangerous one goes red
        // — the same red every destructive confirmation in the app wears.
        tint(dialog, DialogInterface.BUTTON_NEGATIVE, Theme.getColor(Theme.key_text_RedBold));
    }

    /**
     * What the user gets instead of the file opening.
     *
     * <h3>Why this is not step one of the pair above</h3>
     *
     * The pair above stands between the user and a file they already decided to
     * open. This one interrupts an ordinary tap on an ordinary-looking file
     * row: the person behind it is not making a decision yet, they are opening
     * an attachment, which is a reflex.
     *
     * <h3>The three seconds</h3>
     *
     * Both buttons are inert while the countdown runs, so a tap already in
     * flight — the second half of the double-tap that opened this dialog —
     * cannot land on a button. The counter runs on the SAFE button: it is the
     * one the user should end up pressing, so it is the one that explains the
     * wait; a countdown on "install anyway" would be a three-second trailer
     * for the escape hatch.
     *
     * @param title verdict-specific title, or null for the generic one. The
     *              words come from the verdict, not from this screen: a
     *              SUSPICIOUS file must not be called a virus (that claim,
     *              made about a clean third-party app, is both untrue and a
     *              Play Misrepresentation problem).
     */
    public static void showVirusWarning(Activity activity, File file, String title, String body,
                                        ReleaseListener listener) {
        // One fixed warning for every dangerous or unknown installer, in the
        // owner's words (2026-10-16): it is hedged ("unknown APK files MAY
        // steal...", not "this is a virus"), so it is both true of any APK and
        // free of the Play Misrepresentation problem the per-verdict title
        // guarded against. The passed title/body (the per-finding wording) are
        // no longer shown here.
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTopImage(new GlyphDrawable(JacIcons.Glyph.VIRUS), JacTheme.danger(activity))
                .setTitle(JacStrings.get(activity, R.string.jac_apk_warn_title))
                .setMessage(JacStrings.get(activity, R.string.jac_apk_warn_body))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_virus_close), null)
                .setNegativeButton(JacStrings.get(activity, R.string.jac_virus_install_anyway),
                        (d, which) -> showConfirm(activity, file, listener))
                .create();
        // A spoken warning, once, as the dialog lands — always in Uzbek, the
        // channel that still reaches someone whose tap is already in flight and
        // whose eyes have moved on. Replaces the alarm tone that was here.
        warningShowing = true;
        dialog.setOnDismissListener(d -> onWarningDismissed());
        JacAlarm.voice(activity, R.raw.jac_voice_file);
        dialog.show();
        // The escape hatch is allowed but not offered: muted grey, not red —
        // red is a warning colour, and putting it on the way out makes the way
        // out the loudest thing on the screen.
        tint(dialog, DialogInterface.BUTTON_NEGATIVE, Theme.getColor(Theme.key_dialogTextGray2));
        startCountdown(activity, dialog);
    }

    /**
     * Step 2 of 2 — what happens to you.
     *
     * Not public. There is no way to reach this screen except through step one,
     * because a caller that could open it directly would be a way to turn the
     * two-step gate into a one-step one.
     */
    private static void showConfirm(Activity activity, File file, ReleaseListener listener) {
        // The acknowledgement, as Telegram's own dialog checkbox — the same
        // cell every "don't ask again" in the app uses, configured the way
        // AlertsCreator configures it.
        CheckBoxCell acknowledge = new CheckBoxCell(activity, 1);
        acknowledge.setMultiline(true);
        acknowledge.getTextView().getLayoutParams().width = LayoutHelper.MATCH_PARENT;
        acknowledge.getTextView().setSingleLine(false);
        acknowledge.getTextView().setMaxLines(3);
        acknowledge.getTextView().setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        acknowledge.setText(JacStrings.get(activity, R.string.jac_quarantine_confirm_checkbox), "", false, false);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTopImage(new GlyphDrawable(JacIcons.Glyph.WARNING), JacTheme.danger(activity))
                .setTitle(JacStrings.get(activity, R.string.jac_quarantine_confirm_title))
                .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 2, 2))
                .setMessage(JacStrings.get(activity, R.string.jac_quarantine_confirm_body))
                .setView(acknowledge)
                .setPositiveButton(JacStrings.get(activity, R.string.jac_quarantine_confirm_action),
                        (d, which) -> listener.onReleaseConfirmed(file))
                .setNegativeButton(JacStrings.get(activity, R.string.jac_quarantine_confirm_cancel), null)
                .create();
        // Keep the auto-detection modal at bay through step two as well.
        warningShowing = true;
        dialog.setOnDismissListener(d -> onWarningDismissed());
        dialog.show();

        // Disabled until acknowledged, and visibly so. A button that looks
        // ready and then does nothing reads as a bug, and the user's next move
        // is to tap it again rather than to read the line above it.
        final View danger = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        tint(dialog, DialogInterface.BUTTON_POSITIVE, Theme.getColor(Theme.key_text_RedBold));
        setEnabled(danger, false);
        acknowledge.setOnClickListener(v -> {
            acknowledge.setChecked(!acknowledge.isChecked(), true);
            setEnabled(danger, acknowledge.isChecked());
        });
    }

    private static void tint(AlertDialog dialog, int button, int color) {
        View view = dialog.getButton(button);
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(color);
        }
    }

    private static void setEnabled(View button, boolean enabled) {
        if (button != null) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    /**
     * Hold both buttons inert for {@link #COUNTDOWN_SECONDS}, counting down on
     * the safe one, so the second half of the double-tap that opened this
     * dialog cannot land on a button.
     *
     * <p>Silent: the spoken voice warning is the sound now, and the ticking
     * countdown tone that used to run under it was a second sound on top of the
     * voice (the owner asked for it gone, 2026-10-16). The countdown is shown,
     * not heard.
     */
    private static void startCountdown(Activity activity, AlertDialog dialog) {
        final View close = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        final View proceed = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (!(close instanceof TextView)) {
            return; // no buttons, nothing to hold
        }
        final TextView safe = (TextView) close;
        final String label = safe.getText().toString();
        final int[] remaining = {COUNTDOWN_SECONDS};

        setEnabled(close, false);
        setEnabled(proceed, false);
        safe.setText(label + "  " + remaining[0]);

        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            // The dialog can be gone: dismissed by the caller, or the activity
            // torn down under it. Touching the views then is a leak at best,
            // so the countdown checks before every step and simply stops.
            if (!dialog.isShowing()) {
                return;
            }
            remaining[0]--;
            if (remaining[0] > 0) {
                safe.setText(label + "  " + remaining[0]);
                safe.postDelayed(tick[0], 1000L);
            } else {
                safe.setText(label);
                setEnabled(close, true);
                setEnabled(proceed, true);
            }
        };
        safe.postDelayed(tick[0], 1000L);
    }

    // ------------------------------------------------------------------
    // Keeping out of the auto-detection modal's way
    // ------------------------------------------------------------------

    /** True while any of these warning dialogs is on screen. */
    private static volatile boolean warningShowing;

    /**
     * Whether a file/link tap-warning is up right now, so {@link ThreatAlert}
     * (the device-scan modal) can hold back rather than stack a second dialog
     * and a second alarm over this one.
     */
    public static boolean isWarningShowing() {
        return warningShowing;
    }

    /**
     * Dismiss handler for every warning here: silence the voice and the
     * vibration under it.
     *
     * <p>It deliberately does not then raise the device-scan modal. The person
     * just dealt with this file through the tap-warning; the auto-detection
     * modal belongs to the next app open, not to the instant this closes, so a
     * threat it was holding back stays held until then (the owner wanted the
     * two not to stack, 2026-10-16).
     */
    private static void onWarningDismissed() {
        warningShowing = false;
        JacAlarm.stop();
    }

    /**
     * The verdict glyph for the dialog's top band: {@link JacIcons} geometry,
     * white, on the fixed verdict red the builder paints behind it. Sized like
     * upstream's own top-image drawables so {@link AlertDialog} centres it in
     * the band without special-casing.
     */
    private static final class GlyphDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final JacIcons.Glyph glyph;

        GlyphDrawable(JacIcons.Glyph glyph) {
            this.glyph = glyph;
            paint.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            box.set(getBounds());
            JacIcons.draw(canvas, glyph, box, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return AndroidUtilities.dp(52);
        }

        @Override
        public int getIntrinsicHeight() {
            return AndroidUtilities.dp(52);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

}
