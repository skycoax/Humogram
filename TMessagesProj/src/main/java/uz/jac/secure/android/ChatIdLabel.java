package uz.jac.secure.android;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

/**
 * The other side's numeric ID in a chat's header, in front of its status:
 * "ID: 123456789 · last seen recently".
 *
 * <h3>Why</h3>
 *
 * People report scammers with screenshots, and a screenshot of a chat shows a
 * name and a photo — both of which the scammer chose and can change in a
 * second. The ID is the one thing about an account that never changes, so a
 * screenshot that carries it can be matched to the account later by the
 * person, the police or Telegram's abuse desk. It goes first so that a long
 * status is what gets cut short, never the ID.
 *
 * <h3>Which number</h3>
 *
 * A person or bot: its user ID. A group or channel: the form bots and every
 * ID-lookup tool print — {@code -100…} for channels and supergroups,
 * {@code -…} for a basic group — so it matches what someone checking it will
 * find.
 *
 * <p>Nothing is fetched: the ID is part of the peer Telegram already sent.
 * Hidden for your own Saved Messages, which needs no proof of who it is with.
 * Off with the switch on the Humogram page.
 */
public final class ChatIdLabel {

    private static final String SEPARATOR = " · ";

    private ChatIdLabel() {
    }

    /** {@code status} with the user's ID in front, or unchanged when the label is off. */
    public static CharSequence prepend(Context context, TLRPC.User user, CharSequence status) {
        if (user == null || UserObject.isUserSelf(user) || context == null
                || !HumogramConfig.isChatIdInHeader(context)) {
            return status;
        }
        return join(String.valueOf(user.id), status);
    }

    /** {@code status} with the chat's ID in front, or unchanged when the label is off. */
    public static CharSequence prepend(Context context, TLRPC.Chat chat, CharSequence status) {
        if (chat == null || context == null || !HumogramConfig.isChatIdInHeader(context)) {
            return status;
        }
        final String id = ChatObject.isChannel(chat) ? "-100" + chat.id : "-" + chat.id;
        return join(id, status);
    }

    private static CharSequence join(String id, CharSequence status) {
        final String label = "ID: " + id;
        if (TextUtils.isEmpty(status)) {
            return label;
        }
        return TextUtils.concat(label, SEPARATOR, status);
    }
}
