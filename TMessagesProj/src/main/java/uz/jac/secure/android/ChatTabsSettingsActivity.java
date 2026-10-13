package uz.jac.secure.android;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Which chat-type tabs ({@link ChatTabs}) sit over the chat list: one switch
 * for all of them and a tick per tab. Opened from the Humogram page and from
 * "Edit tabs" on a tab's long-press menu.
 *
 * <p>Upstream cells only, the way {@link HumogramSettingsActivity} is built:
 * a {@code TextCheckCell} for the switch, round {@code CheckBoxCell}s for the
 * tabs, a {@code TextInfoPrivacyCell} saying what these tabs are not (Telegram
 * folders), and the section cards drawn by the list.
 */
public class ChatTabsSettingsActivity extends UniversalFragment {

    private static final int CHECK_TABS = 1;
    /** Plus the {@link ChatTabs} kind. */
    private static final int TAB_BASE = 100;

    @Override
    public View createView(Context context) {
        fragmentView = super.createView(context);
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        return fragmentView;
    }

    @Override
    protected CharSequence getTitle() {
        return JacStrings.get(getContext(), R.string.jac_tabs_title);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final Context context = getContext();
        final boolean on = HumogramConfig.isChatTabs(context);
        items.add(UItem.asCheck(CHECK_TABS, JacStrings.get(context, R.string.jac_tabs_switch)).setChecked(on));
        items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_tabs_switch_info)));
        if (!on) {
            return;
        }
        items.add(UItem.asHeader(JacStrings.get(context, R.string.jac_tabs_which)));
        final int mask = HumogramConfig.getChatTabsMask(context);
        for (int kind : ChatTabs.KINDS) {
            items.add(UItem.asRoundCheckbox(TAB_BASE + kind, ChatTabs.title(context, kind))
                    .setChecked((mask & ChatTabs.bit(kind)) != 0));
        }
        items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_tabs_note)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final Context context = getContext();
        if (item.id == CHECK_TABS) {
            final boolean on = !HumogramConfig.isChatTabs(context);
            HumogramConfig.setChatTabs(context, on);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(on);
            }
            item.checked = on;
            listView.adapter.update(true);
            return;
        }
        if (item.id > TAB_BASE) {
            final int kind = item.id - TAB_BASE;
            final int mask = HumogramConfig.getChatTabsMask(context) ^ ChatTabs.bit(kind);
            HumogramConfig.setChatTabsMask(context, mask);
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
