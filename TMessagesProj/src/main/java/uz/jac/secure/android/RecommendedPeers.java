package uz.jac.secure.android;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Cyber-safety channels and bots on Telegram's main Settings screen, in the
 * card with Wallet, right after it: each with the avatar and the name it has
 * on Telegram, and its @username underneath. A tap opens it in the app.
 *
 * <h3>Telegram's own data, nothing stored</h3>
 *
 * The rows show what Telegram says about the account today, the way a chat
 * list would: the username is resolved with the same request a t.me link
 * makes ({@code contacts.resolveUsername}, through upstream's
 * {@code UserNameResolver}), once per process, and the avatar is loaded by
 * {@link BackupImageView} like any other. A rename or a new photo on Telegram
 * shows up here by itself.
 *
 * <p>A row appears only once its username has resolved. One that does not —
 * offline, or an account renamed or gone — is left out rather than shown as a
 * link to nowhere.
 *
 * <h3>Not ours</h3>
 *
 * These belong to their owners. The card's footnote says Humogram is not
 * connected with them, so a settings row with an official crest on it cannot
 * read as the app claiming to be — or to speak for — that organisation.
 *
 * <h3>Then ours</h3>
 *
 * The list ends with "Humogram taklif va baholash", right under the last bot:
 * Google Play's own rating sheet ({@link RateApp}), in the same settings row
 * upstream uses for everything else on this screen. It is there whether or not
 * any of the usernames have resolved.
 */
public final class RecommendedPeers {

    /** Listed in this order after Wallet: the owner's order (2026-10-16). */
    static final String[] USERNAMES = {
            "Press_Secretary_Uz",
            "iivuz",
            "cyberpolice_iiv",
            "jizzax_kiber",
            "JIZZAXKIBERMASLAHATBOT",
            "JizzaxKiberT_bot",
    };

    /** Row ids, clear of SettingsActivity's own (1 … 26) and of Wallet's (its bot id, hashed). */
    private static final int ID_BASE = 3_000;
    /** "Humogram taklif va baholash", under the last of them. */
    private static final int ID_RATE = ID_BASE + 100;

    /** Per account: username → peer id, or 0 while asking / after a failed answer. UI thread. */
    @SuppressWarnings("unchecked")
    private static final HashMap<String, Long>[] resolved = new HashMap[UserConfig.MAX_ACCOUNT_COUNT];
    @SuppressWarnings("unchecked")
    private static final HashMap<String, Boolean>[] asked = new HashMap[UserConfig.MAX_ACCOUNT_COUNT];

    private RecommendedPeers() {
    }

    /**
     * Add a row for each username Telegram has answered for, the rating row
     * and the footnote after them; ask about the rest. {@code onResolved} runs on the UI thread
     * when an answer arrives, so the screen can fill its list again.
     */
    public static void addItems(Context context, int account, ArrayList<UItem> items, Runnable onResolved) {
        if (context == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        int added = 0;
        for (int i = 0; i < USERNAMES.length; i++) {
            final String username = USERNAMES[i];
            final TLObject peer = peerOf(account, username);
            if (peer == null) {
                ask(account, controller, username, onResolved);
                continue;
            }
            items.add(PeerCell.Factory.of(ID_BASE + i, account, username, peer));
            added++;
        }
        items.add(SettingsActivity.SettingCell.Factory.of(ID_RATE,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.settings_stars, JacStrings.get(context, R.string.jac_rate_row)));
        if (added > 0) {
            items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_peers_note)));
        }
    }

    /** True when the row was one of these and has been handled. */
    public static boolean onClick(Context context, UItem item) {
        if (item.id == ID_RATE) {
            // Rated from here, so the chat list need not ask any more.
            HumogramConfig.setRateHintDone(context);
            if (context instanceof Activity) {
                RateApp.launch((Activity) context);
            }
            return true;
        }
        final int index = item.id - ID_BASE;
        if (index < 0 || index >= USERNAMES.length || !(item.object instanceof TLObject)) {
            return false;
        }
        // A t.me link, so upstream opens the bot or channel inside the app.
        Browser.openUrl(context, "https://t.me/" + USERNAMES[index]);
        return true;
    }

    private static TLObject peerOf(int account, String username) {
        final MessagesController controller = MessagesController.getInstance(account);
        final HashMap<String, Long> map = resolved[account];
        final Long id = map != null ? map.get(username) : null;
        if (id != null && id != 0) {
            final TLObject peer = id > 0 ? controller.getUser(id) : controller.getChat(-id);
            if (peer != null) {
                return peer;
            }
        }
        // Already known to the app (a chat with it, an earlier lookup).
        return controller.getUserOrChat(username);
    }

    private static void ask(int account, MessagesController controller, String username, Runnable onResolved) {
        if (asked[account] == null) {
            asked[account] = new HashMap<>();
        }
        if (asked[account].containsKey(username)) {
            return; // once per process: a failure is not retried on every refill
        }
        asked[account].put(username, Boolean.TRUE);
        controller.getUserNameResolver().resolve(username, peerId -> {
            if (resolved[account] == null) {
                resolved[account] = new HashMap<>();
            }
            final boolean found = peerId != null && peerId != 0 && peerId != Long.MAX_VALUE;
            resolved[account].put(username, found ? peerId : 0L);
            if (found && onResolved != null) {
                onResolved.run();
            }
        });
    }

    /**
     * The row: the same size, insets and type as the Wallet row above it
     * ({@code SettingsActivity.SettingCell}), with the account's avatar where
     * that row has its icon — drawn by {@link BackupImageView} and
     * {@link AvatarDrawable}, as the account rows on the same screen draw theirs.
     */
    public static class PeerCell extends LinearLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final BackupImageView avatarView;
        private final TextView titleView;
        private final TextView subtitleView;

        public PeerCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(HORIZONTAL);

            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(14));

            final LinearLayout textLayout = new LinearLayout(context);
            textLayout.setOrientation(VERTICAL);

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            if (LocaleController.isRTL) {
                titleView.setGravity(Gravity.RIGHT);
                subtitleView.setGravity(Gravity.RIGHT);
                addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL, 20, 0, 18, 0));
                addView(avatarView, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 18, 0));
            } else {
                addView(avatarView, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL | Gravity.LEFT, 18, 0, 0, 0));
                addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL, 18, 0, 20, 0));
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        }

        void set(int account, String username, TLObject peer) {
            avatarDrawable.setInfo(account, peer);
            avatarView.getImageReceiver().setCurrentAccount(account);
            avatarView.setForUserOrChat(peer, avatarDrawable);
            final String name;
            if (peer instanceof TLRPC.User) {
                name = UserObject.getUserName((TLRPC.User) peer);
            } else if (peer instanceof TLRPC.Chat) {
                name = ((TLRPC.Chat) peer).title;
            } else {
                name = null;
            }
            titleView.setText(TextUtils.isEmpty(name) ? "@" + username : name);
            subtitleView.setText("@" + username);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // SettingCell's two-line height.
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(60), MeasureSpec.EXACTLY));
        }

        public static class Factory extends UItem.UItemFactory<PeerCell> {
            static { setup(new Factory()); }

            @Override
            public PeerCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new PeerCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (view instanceof PeerCell && item.object instanceof TLObject) {
                    ((PeerCell) view).set(item.intValue, String.valueOf(item.text), (TLObject) item.object);
                }
            }

            static UItem of(int id, int account, String username, TLObject peer) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.intValue = account;
                item.text = username;
                item.object = peer;
                return item;
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                // A new user or chat object (renamed, new photo) rebinds the row.
                return a.id == b.id && a.object == b.object && a.intValue == b.intValue;
            }
        }
    }
}
