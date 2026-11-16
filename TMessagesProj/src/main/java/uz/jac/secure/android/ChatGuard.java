package uz.jac.secure.android;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import uz.jac.secure.core.link.ChatLists;

/**
 * Which channels and groups the app warns about before they are opened or
 * joined — and which accounts and bots before a chat with them is opened —
 * and which ones the user has already been warned about and chose to go into
 * anyway. {@link ChatGuardUi} does the asking.
 *
 * <h3>Accounts are chats too</h3>
 *
 * The way the Bot API counts them: a private chat's id is the account's own
 * id, positive, where a channel's or group's is negative. So one list holds
 * both, one set of approvals keys both, and a "bank employee", a "customs
 * officer" or a hacked friend asking to borrow money is warned about the way
 * a fake-bonus channel is.
 *
 * <h3>Two sources</h3>
 *
 * The owner's list — the {@code chats} section of the signed document the link
 * guard downloads ({@link LinkGuard#chatLists()}), built from users' reports
 * the owner has checked — and Telegram's own SCAM and FAKE marks on a chat,
 * which upstream only draws as a small badge. Both are matched on the phone:
 * nothing about which chats the user opens is sent anywhere.
 *
 * <h3>A warning, never a wall</h3>
 *
 * Telegram's API terms (§1.4) forbid interfering with the basic functionality
 * of Telegram, and reading or joining a chat is exactly that. So the guard
 * only ever asks: two confirmations, then the chat opens as it always would.
 * The answer is remembered for this process only — the next time the app
 * starts, the chat asks again.
 *
 * <p>Any thread; nothing here blocks.
 */
public final class ChatGuard {

    public static final int SOURCE_LIST = 1, SOURCE_TELEGRAM_SCAM = 2, SOURCE_TELEGRAM_FAKE = 3;

    /** Why a chat is warned about. */
    public static final class Flag {
        /** {@code SOURCE_*}. */
        public final int source;
        /** For {@link #SOURCE_LIST}: one of {@link ChatLists#REASONS}; otherwise null. */
        public final String reason;

        Flag(int source, String reason) {
            this.source = source;
            this.reason = reason;
        }

        /** Whether an appeal can lift it: only the owner's list can be changed by the owner. */
        public boolean appealable() {
            return source == SOURCE_LIST;
        }
    }

    /** Invite links seen per chat id, so a group known only by its invite is recognised once opened. */
    private static final int MAX_NOTED_INVITES = 64;

    private static final Object LOCK = new Object();
    // Guarded by LOCK.
    private static final Map<Long, String> invites = new LinkedHashMap<Long, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > MAX_NOTED_INVITES;
        }
    };
    private static final Set<Long> approvedPeers = new HashSet<>();
    private static final Set<String> approvedInvites = new HashSet<>();

    private ChatGuard() {
    }

    /** The switch ({@link HumogramConfig#isChatGuard}, default on). */
    public static boolean isOn() {
        try {
            final Context context = ApplicationLoader.applicationContext;
            return context != null && HumogramConfig.isChatGuard(context);
        } catch (Throwable t) {
            return true;
        }
    }

    /** The Bot API id of a chat: {@code -100…} for a channel or supergroup, {@code -…} for a basic group. */
    public static long peerOf(TLRPC.Chat chat) {
        return ChatObject.isChannel(chat) ? ChatLists.peerOfChannel(chat.id) : ChatLists.peerOfGroup(chat.id);
    }

    /** The chat's public usernames, the editable one first; empty when it has none. */
    public static List<String> usernamesOf(TLRPC.Chat chat) {
        final ArrayList<String> out = new ArrayList<>(2);
        if (chat == null) {
            return out;
        }
        if (chat.username != null && !chat.username.isEmpty()) {
            out.add(chat.username);
        }
        if (chat.usernames != null) {
            for (TLRPC.TL_username u : chat.usernames) {
                if (u != null && u.active && u.username != null && !u.username.isEmpty() && !out.contains(u.username)) {
                    out.add(u.username);
                }
            }
        }
        return out;
    }

    /** The account's or bot's public usernames, the editable one first; empty when it has none. */
    public static List<String> usernamesOf(TLRPC.User user) {
        final ArrayList<String> out = new ArrayList<>(2);
        if (user == null) {
            return out;
        }
        if (user.username != null && !user.username.isEmpty()) {
            out.add(user.username);
        }
        if (user.usernames != null) {
            for (TLRPC.TL_username u : user.usernames) {
                if (u != null && u.active && u.username != null && !u.username.isEmpty() && !out.contains(u.username)) {
                    out.add(u.username);
                }
            }
        }
        return out;
    }

    /**
     * Why a chat with this account or bot is warned about, or null: the
     * switch is off, it is the user's own account or one of Telegram's
     * service accounts, or neither the owner's list nor Telegram marks it.
     * Never throws.
     */
    public static Flag flagOf(TLRPC.User user) {
        if (user == null || user.id <= 0 || user.self || UserObject.isService(user.id) || !isOn()) {
            return null;
        }
        try {
            final ChatLists.Entry entry = LinkGuard.chatLists().match(
                    ChatLists.peerOfUser(user.id), usernamesOf(user), null);
            if (entry != null) {
                return new Flag(SOURCE_LIST, entry.getReason());
            }
            if (user.scam) {
                return new Flag(SOURCE_TELEGRAM_SCAM, null);
            }
            if (user.fake) {
                return new Flag(SOURCE_TELEGRAM_FAKE, null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Why this chat is warned about, or null: the switch is off, or neither
     * the owner's list nor Telegram marks it. Never throws.
     *
     * @param inviteHash the invite link it was opened by, if any; otherwise
     *                   the one {@link #noteInvite} remembered for it
     */
    public static Flag flagOf(TLRPC.Chat chat, String inviteHash) {
        if (chat == null || !isOn()) {
            return null;
        }
        try {
            final String invite = inviteHash != null ? inviteHash : noteFor(chat.id);
            final ChatLists.Entry entry = LinkGuard.chatLists().match(peerOf(chat), usernamesOf(chat), invite);
            if (entry != null) {
                return new Flag(SOURCE_LIST, entry.getReason());
            }
            if (chat.scam) {
                return new Flag(SOURCE_TELEGRAM_SCAM, null);
            }
            if (chat.fake) {
                return new Flag(SOURCE_TELEGRAM_FAKE, null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * The same for an invite link's preview, before the user is in the group:
     * its hash against the list, the chat it names (when Telegram says), and
     * Telegram's marks on the preview itself.
     */
    public static Flag flagOfInvite(String inviteHash, TLRPC.ChatInvite invite) {
        if (!isOn()) {
            return null;
        }
        try {
            if (invite != null && invite.chat != null) {
                final Flag byChat = flagOf(invite.chat, inviteHash);
                if (byChat != null) {
                    return byChat;
                }
            }
            final ChatLists.Entry entry = LinkGuard.chatLists().forInvite(inviteHash);
            if (entry != null) {
                return new Flag(SOURCE_LIST, entry.getReason());
            }
            if (invite != null && invite.scam) {
                return new Flag(SOURCE_TELEGRAM_SCAM, null);
            }
            if (invite != null && invite.fake) {
                return new Flag(SOURCE_TELEGRAM_FAKE, null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * From {@code LaunchActivity}, when an invite link resolves: which link
     * this chat was reached by, so that once it is open the chat can be
     * matched by the link's hash as well as by its id, and a report from
     * inside it can carry the link the owner needs to look. Memory only.
     */
    public static void noteInvite(String inviteHash, TLRPC.ChatInvite invite) {
        if (inviteHash == null || inviteHash.isEmpty() || invite == null || invite.chat == null) {
            return;
        }
        synchronized (LOCK) {
            invites.put(invite.chat.id, inviteHash);
        }
    }

    /** The invite link this chat was last reached by in this process, or null. */
    public static String noteFor(long chatId) {
        synchronized (LOCK) {
            return invites.get(chatId);
        }
    }

    public static boolean isApproved(TLRPC.Chat chat) {
        if (chat == null) {
            return false;
        }
        synchronized (LOCK) {
            return approvedPeers.contains(peerOf(chat));
        }
    }

    public static boolean isApproved(TLRPC.User user) {
        if (user == null) {
            return false;
        }
        synchronized (LOCK) {
            return approvedPeers.contains(ChatLists.peerOfUser(user.id));
        }
    }

    /** Both confirmations given for a chat with this account: do not ask again until the app restarts. */
    static void approve(TLRPC.User user) {
        if (user == null) {
            return;
        }
        synchronized (LOCK) {
            approvedPeers.add(ChatLists.peerOfUser(user.id));
        }
    }

    public static boolean isInviteApproved(String inviteHash) {
        if (inviteHash == null) {
            return false;
        }
        synchronized (LOCK) {
            return approvedInvites.contains(inviteHash);
        }
    }

    /** Both confirmations given for this chat: do not ask again until the app restarts. */
    static void approve(TLRPC.Chat chat, String inviteHash) {
        synchronized (LOCK) {
            if (chat != null) {
                approvedPeers.add(peerOf(chat));
            }
            if (inviteHash != null) {
                approvedInvites.add(inviteHash);
            }
        }
    }
}
