package uz.jac.secure.android;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * "Check phone" on the welcome screen: the device virus scanner, before
 * anyone has signed in.
 *
 * <h3>No account, by construction</h3>
 *
 * The scanner itself never needed one — {@link DeviceScanner} reads the
 * package manager and Humogram's own folders, judges with the bundled core,
 * and keeps its report and settings in local files ({@link HumogramConfig}),
 * with no network step at all. What did touch the account was the Humogram
 * page around it (the protection percentage counts the cloud password; the
 * other switches belong to a signed-in user), so this page hosts only
 * {@link DeviceScanSection}, with the percentage left out. Threats found here
 * alarm through {@link ThreatAlert} exactly as they do after sign-in.
 *
 * <p>It opens scanning. Back — or "Continue to sign in" at the bottom —
 * returns to the welcome screen, which is still underneath.
 */
public class PhoneCheckActivity extends UniversalFragment {

    private static final int BUTTON_CONTINUE = 2;

    private DeviceScanSection scan;
    private boolean scanned;

    @Override
    public View createView(Context context) {
        scan = new DeviceScanSection(this, context, animated -> {
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(animated);
            }
        }, false);
        fragmentView = super.createView(context);
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        scan.start(context);
        if (!scanned) {
            scanned = true;
            scan.scanNow();
        }
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (scan != null) {
            scan.onResume();
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (scan != null) {
            scan.onDestroy();
        }
        super.onFragmentDestroy();
    }

    @Override
    protected CharSequence getTitle() {
        return JacStrings.get(getContext(), R.string.jac_check_phone);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (scan != null) {
            scan.fillItems(items, null);
        }
        items.add(UItem.asButton(BUTTON_CONTINUE, JacStrings.get(getContext(), R.string.jac_check_phone_continue)).accent());
        items.add(UItem.asShadow(JacStrings.get(getContext(), R.string.jac_check_phone_info)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (scan != null && scan.onClick(item, view)) {
            return;
        }
        if (item.id == BUTTON_CONTINUE) {
            finishFragment();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
