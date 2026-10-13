package uz.jac.secure.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * The chat-type tabs over the chat list, the way Plus Messenger has them:
 * All · Private · Groups · Channels · Bots · Favourites · Unread · Admin, and
 * after them the user's own Telegram folders.
 *
 * <h3>Built from Telegram's folders, kept away from them</h3>
 *
 * Each tab is a {@link MessagesController.DialogFilter} — the object the chat
 * list already knows how to page through, filter, count and animate — so the
 * tab strip is upstream's {@code FilterTabsView} and every page is upstream's
 * folder page. Nothing here draws a tab or a list.
 *
 * <p>What a tab must never be is a <em>Telegram</em> folder. Folders sync to
 * the account and to every other device; these are a view of this phone's chat
 * list and nothing more. So they never enter
 * {@link MessagesController#getDialogFilters()} — the list everything else
 * (folder settings, "add to folder", the server sync) reads. The chat list
 * asks {@link #merge} for its tabs instead, and the three upstream paths that
 * would write a folder to the server check {@link #isOurs} first:
 * {@code FilterCreateActivity.saveFilterToServer} refuses one outright, and the
 * chat list treats pinning inside one of these tabs as pinning in the main
 * list, which is what it means here.
 *
 * <p>Ids are negative ({@code -1000 - kind}); Telegram's folder ids are
 * positive and the "All chats" one is 0, and upstream already treats a
 * negative id as "not a server folder" (see
 * {@code MessagesController.checkChatlistFolderUpdate}).
 */
public final class ChatTabs {

    public static final int USERS = 1;
    public static final int GROUPS = 2;
    public static final int CHANNELS = 3;
    public static final int BOTS = 4;
    public static final int FAVORITES = 5;
    public static final int UNREAD = 6;
    public static final int ADMIN = 7;

    /** Tab order, left to right after "All chats". */
    public static final int[] KINDS = {USERS, GROUPS, CHANNELS, BOTS, FAVORITES, UNREAD, ADMIN};

    /** On out of the box: everything but Admin, which most people have nothing in. */
    static final int DEFAULT_MASK = bit(USERS) | bit(GROUPS) | bit(CHANNELS) | bit(BOTS)
            | bit(FAVORITES) | bit(UNREAD);

    private static final int ID_BASE = -1000;

    private static final String KEY_FAVORITES = "tabs_favorites_";

    private static final Tab[][] tabs = new Tab[UserConfig.MAX_ACCOUNT_COUNT][];
    private static final MessagesController.DialogFilter[] allChats =
            new MessagesController.DialogFilter[UserConfig.MAX_ACCOUNT_COUNT];
    @SuppressWarnings("unchecked")
    private static final Set<Long>[] favorites = new Set[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] favoritesOwner = new long[UserConfig.MAX_ACCOUNT_COUNT];

    // Unread counters: one pass over the chat list serves every tab, and is
    // thrown away at the end of the UI message that asked for it.
    private static final int[][] counts = new int[UserConfig.MAX_ACCOUNT_COUNT][];
    private static final boolean[] countsValid = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private ChatTabs() {
    }

    static int bit(int kind) {
        return 1 << kind;
    }

    /** One of our tabs. Never sent anywhere; see the class comment. */
    public static final class Tab extends MessagesController.DialogFilter {

        public final int kind;
        private final int account;

        Tab(int account, int kind) {
            this.account = account;
            this.kind = kind;
            id = ID_BASE - kind;
            switch (kind) {
                case USERS:
                    flags = MessagesController.DIALOG_FILTER_FLAG_CONTACTS
                            | MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
                    break;
                case GROUPS:
                    flags = MessagesController.DIALOG_FILTER_FLAG_GROUPS;
                    break;
                case CHANNELS:
                    flags = MessagesController.DIALOG_FILTER_FLAG_CHANNELS;
                    break;
                case BOTS:
                    flags = MessagesController.DIALOG_FILTER_FLAG_BOTS;
                    break;
                case UNREAD:
                    flags = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
                            | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ;
                    break;
                case ADMIN:
                    flags = MessagesController.DIALOG_FILTER_FLAG_GROUPS
                            | MessagesController.DIALOG_FILTER_FLAG_CHANNELS;
                    break;
                default:
                    flags = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
                    break;
            }
            if (kind != FAVORITES) {
                // The archive stays in the archive, as in the main list; a
                // favourite is shown wherever it lives.
                flags |= MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED;
            }
        }

        @Override
        public boolean includesDialog(AccountInstance accountInstance, long dialogId, TLRPC.Dialog d) {
            if (d == null) {
                return false;
            }
            if (kind == FAVORITES) {
                final Set<Long> set = favorites(account);
                return set.contains(d.id) || set.contains(dialogId);
            }
            // Telegram's own folder rules for everything the flags describe:
            // contacts or not, bots, groups against channels, communities,
            // the archive, unread.
            if (!super.includesDialog(accountInstance, dialogId, d)) {
                return false;
            }
            if (kind == ADMIN) {
                final TLRPC.Chat chat = dialogId < 0
                        ? accountInstance.getMessagesController().getChat(-dialogId) : null;
                return chat != null && (chat.creator || chat.admin_rights != null);
            }
            return true;
        }

        /**
         * Before the list sorts this tab: pin what the main list pins, in its
         * order. Pinning in one of these tabs pins in the main list (see the
         * class comment), so the two cannot disagree. The folder sort puts the
         * lowest number first and the main list the highest, hence the minus.
         */
        public void syncPins(ArrayList<TLRPC.Dialog> all) {
            pinnedDialogs.clear();
            if (all == null) {
                return;
            }
            for (int i = 0, n = all.size(); i < n; i++) {
                final TLRPC.Dialog d = all.get(i);
                if (d != null && d.pinned && d.folder_id == 0 && !(d instanceof TLRPC.TL_dialogFolder)) {
                    pinnedDialogs.put(d.id, -d.pinnedNum);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // What the chat list asks
    // ------------------------------------------------------------------

    public static boolean isOurs(MessagesController.DialogFilter filter) {
        return filter instanceof Tab;
    }

    /**
     * The tabs the chat list shows: Telegram's folders with ours after "All
     * chats". The same list as {@code serverFilters} when tabs are off.
     *
     * <p>Built fresh on every call (it is a dozen references) from instances
     * that are not: a tab must be the same object from one call to the next,
     * because the list holds it as the selected filter and keys its tab
     * position on {@link MessagesController.DialogFilter#localId}.
     */
    public static ArrayList<MessagesController.DialogFilter> merge(int account,
                                                                   ArrayList<MessagesController.DialogFilter> serverFilters) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || !valid(account) || !HumogramConfig.isChatTabs(context)) {
            return serverFilters;
        }
        final ArrayList<Tab> ours = enabledTabs(context, account);
        if (ours.isEmpty()) {
            return serverFilters;
        }
        final ArrayList<MessagesController.DialogFilter> merged =
                new ArrayList<>(serverFilters.size() + ours.size() + 1);
        int defaultAt = -1;
        for (int i = 0; i < serverFilters.size(); i++) {
            if (serverFilters.get(i).isDefault()) {
                defaultAt = i;
                break;
            }
        }
        if (defaultAt < 0) {
            // No Telegram folders at all: the "All chats" tab the strip needs
            // first, as Telegram would add it with the first folder.
            merged.add(allChats(account));
            merged.addAll(ours);
            merged.addAll(serverFilters);
        } else {
            for (int i = 0; i < serverFilters.size(); i++) {
                merged.add(serverFilters.get(i));
                if (i == defaultAt) {
                    merged.addAll(ours);
                }
            }
        }
        return merged;
    }

    private static ArrayList<Tab> enabledTabs(Context context, int account) {
        Tab[] all = tabs[account];
        if (all == null) {
            all = new Tab[KINDS.length];
            for (int i = 0; i < KINDS.length; i++) {
                all[i] = new Tab(account, KINDS[i]);
            }
            tabs[account] = all;
        }
        final int mask = HumogramConfig.getChatTabsMask(context);
        final ArrayList<Tab> out = new ArrayList<>(all.length);
        for (Tab tab : all) {
            if ((mask & bit(tab.kind)) == 0) {
                continue;
            }
            if (tab.kind == FAVORITES && favorites(account).isEmpty()) {
                // Appears with the first favourite: an empty tab would only
                // say "nothing here" about a feature nobody has found yet.
                continue;
            }
            // The language may have changed since last time.
            tab.name = title(context, tab.kind);
            out.add(tab);
        }
        return out;
    }

    private static MessagesController.DialogFilter allChats(int account) {
        MessagesController.DialogFilter filter = allChats[account];
        if (filter == null) {
            filter = new MessagesController.DialogFilter();
            filter.id = 0;
            filter.flags = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
            allChats[account] = filter;
        }
        filter.name = LocaleController.getString(R.string.FilterAllChats);
        return filter;
    }

    public static String title(Context context, int kind) {
        switch (kind) {
            case USERS:
                return JacStrings.get(context, R.string.jac_tabs_users);
            case GROUPS:
                return LocaleController.getString(R.string.FilterGroups);
            case CHANNELS:
                return LocaleController.getString(R.string.FilterChannels);
            case BOTS:
                return LocaleController.getString(R.string.FilterBots);
            case FAVORITES:
                return JacStrings.get(context, R.string.jac_tabs_favorites);
            case UNREAD:
                return LocaleController.getString(R.string.FilterNameUnread);
            case ADMIN:
            default:
                return JacStrings.get(context, R.string.jac_tabs_admin);
        }
    }

    /** The settings-row icon for a kind: Telegram's own folder-type glyphs. */
    public static int icon(int kind) {
        switch (kind) {
            case USERS:
                return R.drawable.msg_folders_private;
            case GROUPS:
                return R.drawable.msg_folders_groups;
            case CHANNELS:
                return R.drawable.msg_folders_channels;
            case BOTS:
                return R.drawable.msg_folders_bots;
            case FAVORITES:
                return R.drawable.msg_fave;
            case UNREAD:
                return R.drawable.msg_markunread;
            case ADMIN:
            default:
                return R.drawable.msg_admins;
        }
    }

    // ------------------------------------------------------------------
    // Unread counters
    // ------------------------------------------------------------------

    /**
     * How many chats in the tab are unread, counted the way the "All chats"
     * tab counts them: a muted chat only when Telegram's "count muted chats"
     * is on, or when it mentions you. UI thread.
     */
    public static int unreadCount(int account, MessagesController.DialogFilter filter) {
        if (!(filter instanceof Tab) || !valid(account)) {
            return 0;
        }
        try {
            if (!countsValid[account] || counts[account] == null) {
                counts[account] = countAll(account);
                countsValid[account] = true;
                // Good for the rest of this UI message only: the tab strip
                // asks for every tab, several times, inside one pass.
                AndroidUtilities.runOnUIThread(() -> countsValid[account] = false);
            }
            return counts[account][((Tab) filter).kind];
        } catch (Throwable t) {
            FileLog.e(t);
            return 0;
        }
    }

    private static int[] countAll(int account) {
        final int[] result = new int[ADMIN + 1];
        final Tab[] all = tabs[account];
        if (all == null) {
            return result;
        }
        final AccountInstance instance = AccountInstance.getInstance(account);
        final MessagesController controller = instance.getMessagesController();
        final boolean countMuted = NotificationsController.getInstance(account).showBadgeMuted;
        final ArrayList<TLRPC.Dialog> dialogs = controller.getAllDialogs();
        for (int i = 0, n = dialogs.size(); i < n; i++) {
            final TLRPC.Dialog d = dialogs.get(i);
            if (!(d instanceof TLRPC.TL_dialog || d instanceof TLRPC.TL_dialogCommunity)) {
                continue;
            }
            if (controller.getDialogUnreadCount(d) <= 0 && !d.unread_mark) {
                continue;
            }
            if (!countMuted && d.unread_mentions_count == 0 && controller.isDialogMuted(d.id, 0)) {
                continue;
            }
            long dialogId = d.id;
            if (DialogObject.isEncryptedDialog(dialogId)) {
                final TLRPC.EncryptedChat encrypted = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                if (encrypted != null) {
                    dialogId = encrypted.user_id;
                }
            }
            for (Tab tab : all) {
                if (tab.includesDialog(instance, dialogId, d)) {
                    result[tab.kind]++;
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Favourites
    // ------------------------------------------------------------------

    /**
     * The account's favourite chats. UI thread. Stored under the signed-in
     * user's ID, not the account slot: whoever signs in to the slot next must
     * not inherit someone else's list.
     */
    static Set<Long> favorites(int account) {
        final long userId = UserConfig.getInstance(account).getClientUserId();
        Set<Long> set = favorites[account];
        if (set == null || favoritesOwner[account] != userId) {
            set = new HashSet<>();
            favoritesOwner[account] = userId;
            try {
                final Set<String> stored = userId == 0 ? null
                        : prefs().getStringSet(KEY_FAVORITES + userId, null);
                if (stored != null) {
                    for (String s : stored) {
                        try {
                            set.add(Long.parseLong(s));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
            favorites[account] = set;
        }
        return set;
    }

    /** Whether the action-mode menu should offer favourites at all. */
    public static boolean isFavoritesOn(int account) {
        final Context context = ApplicationLoader.applicationContext;
        return context != null && valid(account) && HumogramConfig.isChatTabs(context)
                && (HumogramConfig.getChatTabsMask(context) & bit(FAVORITES)) != 0;
    }

    /** True when every one of the chats is a favourite already: the menu offers removal. */
    public static boolean allFavorite(int account, ArrayList<Long> dialogIds) {
        if (!valid(account) || dialogIds == null || dialogIds.isEmpty()) {
            return false;
        }
        final Set<Long> set = favorites(account);
        for (int i = 0; i < dialogIds.size(); i++) {
            if (!set.contains(dialogIds.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Add the chats to favourites, or take them out when they all are already.
     * Returns true when they were added.
     */
    public static boolean toggleFavorites(int account, ArrayList<Long> dialogIds) {
        if (!valid(account) || dialogIds == null || dialogIds.isEmpty()) {
            return false;
        }
        final boolean remove = allFavorite(account, dialogIds);
        final Set<Long> set = favorites(account);
        final boolean wasEmpty = set.isEmpty();
        for (int i = 0; i < dialogIds.size(); i++) {
            if (remove) {
                set.remove(dialogIds.get(i));
            } else {
                set.add(dialogIds.get(i));
            }
        }
        final HashSet<String> stored = new HashSet<>();
        for (Long id : set) {
            stored.add(String.valueOf(id));
        }
        final long userId = UserConfig.getInstance(account).getClientUserId();
        try {
            if (userId != 0) {
                prefs().edit().putStringSet(KEY_FAVORITES + userId, stored).apply();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        if (wasEmpty != set.isEmpty()) {
            // The tab appears with the first favourite and goes with the last.
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated);
        }
        final Tab[] all = tabs[account];
        if (all != null) {
            for (Tab tab : all) {
                if (tab.kind == FAVORITES) {
                    MessagesController.getInstance(account).onFilterUpdate(tab);
                }
            }
        }
        return !remove;
    }

    // ------------------------------------------------------------------
    // Settings changes
    // ------------------------------------------------------------------

    /** After a switch on the Humogram page: every signed-in account's chat list rebuilds its tabs. */
    static void onSettingsChanged() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogFiltersUpdated);
            }
        }
    }

    private static SharedPreferences prefs() {
        return HumogramConfig.prefs(ApplicationLoader.applicationContext);
    }

    private static boolean valid(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
    }
}
