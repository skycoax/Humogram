package uz.jac.secure.android;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Which alarm plays on a threat: the five {@link JacAlarm#SOUNDS}, each heard
 * as it is picked, and a "Play" to hear the chosen one again. Opened from the
 * "Alarm sound" row in the Humogram page's security card, next to the
 * "Sound on threat" switch.
 *
 * <p>Upstream cells only, as {@link ChatTabsSettingsActivity}: radio rows
 * ({@code DialogRadioCell}), an accent button row and a footnote, in the
 * list's own section cards.
 */
public class AlarmSoundActivity extends UniversalFragment {

    private static final int BUTTON_PLAY = 1;
    /** Plus the index into {@link JacAlarm#SOUNDS}. */
    private static final int SOUND_BASE = 100;

    @Override
    public View createView(Context context) {
        fragmentView = super.createView(context);
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        return fragmentView;
    }

    @Override
    public void onPause() {
        super.onPause();
        // A preview must not outlive the page.
        JacAlarm.stop();
    }

    @Override
    protected CharSequence getTitle() {
        return JacStrings.get(getContext(), R.string.jac_alarm_sound);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final Context context = getContext();
        final int selected = HumogramConfig.getAlarmSound(context);
        items.add(UItem.asHeader(JacStrings.get(context, R.string.jac_alarm_sound)));
        for (int i = 0; i < JacAlarm.SOUNDS.length; i++) {
            items.add(UItem.asRadio(SOUND_BASE + i, JacStrings.get(context, JacAlarm.NAMES[i]))
                    .setChecked(i == selected));
        }
        items.add(UItem.asButton(BUTTON_PLAY, R.drawable.msg_round_play_m, JacStrings.get(context, R.string.jac_alarm_play)).accent());
        items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_alarm_sound_info)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final Context context = getContext();
        if (item.id == BUTTON_PLAY) {
            JacAlarm.preview(context, HumogramConfig.getAlarmSound(context));
            return;
        }
        if (item.id >= SOUND_BASE) {
            final int index = item.id - SOUND_BASE;
            HumogramConfig.setAlarmSound(context, index);
            listView.adapter.update(true);
            JacAlarm.preview(context, index);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
