package uz.jac.secure.android;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Humogram's own page: the virus scanner first, then the few choices that
 * make the app feel like the user's rather than a stock client.
 *
 * <p>From the top: the device virus scanner ({@link DeviceScanSection} — the
 * verdict ring with the protection percentage, "Scan now" and whatever the
 * last scan found), because it is the app's main feature and has no page of
 * its own; under any threats, the device check ({@link SecurityCheckupSection}:
 * screen lock, root, USB debugging, security updates, cloud password), whose
 * results count towards the same percentage; one card of security switches
 * (scan at every launch, link protection) with the recommended cyber-safety
 * channel; and the ornament the chat list wears — a suzani border, an
 * eight-point-star lattice, the crescent-and-stars of the Uzbek flag, or none.
 *
 * <h3>Why this is a fragment and not an Activity</h3>
 *
 * It used to be a plain {@code android.app.Activity} that built its own action
 * bar out of a {@code TextView} holding an arrow, its own section headers, its
 * own rounded rows and its own radio buttons. Every one of those already exists
 * upstream, so the hand-made copies could only ever be a near miss: the wrong
 * back arrow with no ripple and no swipe-back, rows at the wrong height with the
 * wrong inset, a "radio" that was a circle drawn in {@code onDraw} rather than
 * the animated {@link org.telegram.ui.Components.RadioButton}, and a screen that
 * pushed onto the task stack instead of sliding in over Settings.
 *
 * <p>So it is a {@link UniversalFragment} now — the same base
 * {@link org.telegram.ui.SettingsActivity} and Telegram's own sub-pages use.
 * The list is described as {@link UItem}s and every pixel is drawn by upstream:
 * {@link org.telegram.ui.Cells.HeaderCell} for the section title,
 * {@link org.telegram.ui.Cells.TextCheckCell} for the switch,
 * {@link org.telegram.ui.Cells.DialogRadioCell} for the options,
 * {@link org.telegram.ui.Cells.TextInfoPrivacyCell} for the footnote, and
 * {@code listView.setSections()} for the rounded cards. That means it inherits
 * the ripple, the dividers, RTL, the collapsing adaptive action bar, theme
 * changes and swipe-back for free, and it cannot drift out of step with the
 * rest of Settings when upstream restyles it.
 */
public class HumogramSettingsActivity extends UniversalFragment {

    /**
     * Row ids. Offset off zero so they cannot collide with an unset
     * {@link UItem#id}, and spaced from the ornament constants so the mapping
     * back is one subtraction.
     */
    private static final int BUTTON_ORNAMENT = 100;

    private static final int BUTTON_CHANNEL = 3;
    private static final int BUTTON_CREATOR = 5;
    private static final int CHECK_LINK_GUARD = 7;

    /**
     * The creator's name, shown in the creator row and the footer. A proper
     * name, so the same in every language rather than a translated string.
     */
    private static final String CREATOR_NAME = "Kamolov Muxammad";
    /** The creator's Telegram handle, opened when the creator row is tapped. */
    private static final String CREATOR_HANDLE = "skycoax";

    private OrnamentPreviewView preview;
    private DeviceScanSection scan;
    private SecurityCheckupSection checkup;

    @Override
    public View createView(Context context) {
        preview = new OrnamentPreviewView(context);
        // Before super.createView: that is where the list is filled first.
        final DeviceScanSection.Host refill = animated -> {
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(animated);
            }
        };
        scan = new DeviceScanSection(this, context, refill);
        checkup = new SecurityCheckupSection(this, refill);

        fragmentView = super.createView(context);

        // Exactly what SettingsActivity does one level up: rounded section
        // cards drawn by the list, so the cells must not paint their own
        // square backgrounds over the corners.
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);

        scan.start(context);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (checkup != null) {
            checkup.onResume();
        }
        if (scan != null) {
            // Re-fills the list as well, which also re-runs the device check
            // after a trip to system settings and picks up a link protection
            // switch flipped on another path.
            scan.onResume();
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (scan != null) {
            scan.onDestroy();
        }
        if (checkup != null) {
            checkup.onDestroy();
        }
        super.onFragmentDestroy();
    }

    @Override
    protected CharSequence getTitle() {
        return JacStrings.get(getContext(), R.string.jac_humogram_settings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final Context context = getContext();
        final int selected = HumogramConfig.getOrnament(context);

        if (scan != null) {
            scan.fillItems(items, checkup != null ? () -> checkup.fillItems(items) : null);
        } else if (checkup != null) {
            checkup.fillItems(items);
        }

        items.add(UItem.asHeader(JacStrings.get(context, R.string.jac_security_header)));
        if (scan != null) {
            items.add(scan.launchCheck(context));
        }
        items.add(UItem.asCheck(CHECK_LINK_GUARD, JacStrings.get(context, R.string.jac_linkguard_title))
                .setChecked(HumogramConfig.isLinkGuard(context)));
        if (!HumogramConfig.SECURITY_CHANNEL.isEmpty()) {
            // Value column names the handle and marks it a recommendation, so
            // the enduring settings surface cannot read as the app's own channel
            // once the one-shot chat-list hint is gone.
            items.add(UItem.asButton(BUTTON_CHANNEL,
                    JacStrings.get(context, R.string.jac_channel),
                    "@" + HumogramConfig.SECURITY_CHANNEL).accent());
        }
        items.add(UItem.asShadow(null));

        // The pattern at the size and strength it is actually drawn, on the
        // colour it is actually drawn over — the choice is made by looking.
        items.add(UItem.asCustom(preview));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(JacStrings.get(context, R.string.jac_ornament_header)));
        addOrnament(items, HumogramConfig.ORNAMENT_SUZANI, R.string.jac_ornament_suzani, selected);
        addOrnament(items, HumogramConfig.ORNAMENT_GEOMETRY, R.string.jac_ornament_geometry, selected);
        addOrnament(items, HumogramConfig.ORNAMENT_FLAG, R.string.jac_ornament_flag, selected);
        addOrnament(items, HumogramConfig.ORNAMENT_OFF, R.string.jac_ornament_off, selected);
        items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_ornament_note)));

        // Creator credit, at the very bottom where an app's "about" line lives.
        // A plain centred shadow, the same cell upstream ends its lists with;
        // the row above it opens the creator's Telegram.
        items.add(UItem.asButton(BUTTON_CREATOR, JacStrings.get(context, R.string.jac_creator), CREATOR_NAME));
        items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_made_by, CREATOR_NAME)));
    }

    private void addOrnament(ArrayList<UItem> items, int ornament, int labelRes, int selected) {
        items.add(UItem
                .asRadio(BUTTON_ORNAMENT + ornament, JacStrings.get(getContext(), labelRes))
                .setChecked(ornament == selected));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (scan != null && scan.onClick(item, view)) {
            return;
        }
        if (checkup != null && checkup.onClick(item)) {
            return;
        }
        if (item.id == BUTTON_CREATOR) {
            org.telegram.messenger.browser.Browser.openUrl(getContext(), "https://t.me/" + CREATOR_HANDLE);
            return;
        }
        if (item.id == CHECK_LINK_GUARD) {
            final boolean on = !HumogramConfig.isLinkGuard(getContext());
            HumogramConfig.setLinkGuard(getContext(), on);
            ((org.telegram.ui.Cells.TextCheckCell) view).setChecked(on);
            // The cell animates, but the adapter's cached item still holds the
            // old value and a scroll-recycled rebind would draw it: flip it too.
            item.checked = on;
            return;
        }
        if (item.id == BUTTON_CHANNEL) {
            // A t.me link, so upstream routes it to the channel inside the app.
            org.telegram.messenger.browser.Browser.openUrl(
                    getContext(), "https://t.me/" + HumogramConfig.SECURITY_CHANNEL);
            return;
        }
        if (item.id < BUTTON_ORNAMENT) {
            return;
        }
        final int ornament = item.id - BUTTON_ORNAMENT;
        if (ornament == HumogramConfig.getOrnament(getContext())) {
            return;
        }
        HumogramConfig.setOrnament(getContext(), ornament);
        // update(true) animates the radio across rather than rebuilding them,
        // because DialogRadioCell is rebound in place when its itemId matches.
        listView.adapter.update(true);
        preview.invalidate();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /**
     * The chosen ornament, drawn by the very code that draws it on the chat
     * list.
     *
     * <p>Nothing here decides how the pattern looks: {@link HumoOrnament} owns
     * the ink, the strength and the fade, and reads the choice out of
     * {@link HumogramConfig} the same way the list does. The cell is the real
     * band at its real height ({@link HumoOrnament#TOP_BAND_DP}) over the real
     * page colour, so a preview that agreed with the chat list yesterday cannot
     * disagree with it tomorrow.
     *
     * <p>Picking "none" leaves the card empty, which is not a missing preview —
     * an unadorned {@code windowBackgroundWhite} is exactly what that setting
     * gives you.
     */
    private static class OrnamentPreviewView extends View {

        private final HumoOrnament ornament = new HumoOrnament();
        private final Path clip = new Path();
        private final RectF bounds = new RectF();

        OrnamentPreviewView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec),
                    dp(HumoOrnament.TOP_BAND_DP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int width = getWidth(), height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            // The section card behind this cell is a round rect drawn by the
            // list at exactly these bounds, so the band has to be clipped to
            // the same shape or it spills into the corners.
            bounds.set(0, 0, width, height);
            clip.rewind();
            clip.addRoundRect(bounds, dp(16), dp(16), Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            ornament.drawBand(canvas, getContext(), width, 0, height,
                    HumoOrnament.FADE_DOWN,
                    Theme.getColor(Theme.key_windowBackgroundWhite), 1f);
            canvas.restore();
        }
    }
}
