package uz.jac.secure.android;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TypefaceSpan;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import uz.jac.secure.core.link.ChatLists;

/**
 * Everything the user sees of the chat guard: two confirmations in front of a
 * channel, group, account or bot that the owner's list or Telegram marks as a
 * scam, and the "report" and "marked by mistake" requests.
 *
 * <p>{@link ChatGuard} decides; this class asks. It is called from a few
 * one-line patches in upstream — {@code ChatActivity} when a chat becomes
 * visible, when its join button is pressed and from its menu, and
 * {@code LaunchActivity} before an invite link's join sheet — and leaves
 * upstream exactly as it was when the guard has nothing to say.
 *
 * <h3>The same two steps as a red link</h3>
 *
 * Built from the parts {@link LinkGuardUi} builds its confirmations from —
 * upstream's {@link AlertDialog} with the red band, upstream's
 * {@link CheckBoxCell}, the same three-second hold — so a warning about a chat
 * reads as the same app warning about the same kind of danger. Step one says
 * why; the safe way out is the button under the thumb. Step two will not
 * proceed on a tap: the checkbox first, and the hold.
 *
 * <h3>Opening is not blocked, only asked about</h3>
 *
 * Reading or joining a chat is basic Telegram functionality, which the API
 * terms (§1.4) forbid a client to interfere with. So once both confirmations
 * are given the chat opens or the join goes through exactly as upstream would
 * have done it, and the answer holds until the app restarts. Backing out of a
 * chat that was being opened closes it.
 *
 * <h3>The words</h3>
 *
 * "Humogram considers this dangerous on users' reports" — not "these are
 * scammers". The list is a judgement made on reports, and it says so; next to
 * it is the way to say the judgement is wrong.
 */
public final class ChatGuardUi {

    /** The reasons a chat is reported for, in the order the picker shows them; {@code ChatLists.REASONS}. */
    static final String[] REASONS = {"scam", "romance", "phishing", "malware", "fake", "other"};

    /**
     * The same for an account or a bot, most common first: what people in
     * Uzbekistan lose money to in private messages — a "seller" or "bank"
     * that wants a transfer or a code, a friend's hacked account asking to
     * borrow — and only then the rest.
     */
    static final String[] USER_REASONS = {"scam", "phishing", "hacked", "fake", "romance", "malware", "other"};

    private static final int MODE_OPEN = 0, MODE_JOIN = 1;

    /** Seconds step two holds both buttons. */
    private static final int COUNTDOWN_SECONDS = 3;

    /** The confirmation on screen and the chat it is about. UI thread only. */
    private static WeakReference<AlertDialog> asking;
    private static String askingKey;

    private ChatGuardUi() {
    }

    /** The words for a reason code, as the picker and the warning show it. */
    static String reasonLabel(Context context, String reason) {
        final int res;
        switch (reason != null ? reason : "other") {
            case "scam":
                res = R.string.jac_chat_reason_scam;
                break;
            case "romance":
                res = R.string.jac_chat_reason_romance;
                break;
            case "phishing":
                res = R.string.jac_chat_reason_phishing;
                break;
            case "malware":
                res = R.string.jac_chat_reason_malware;
                break;
            case "fake":
                res = R.string.jac_chat_reason_fake;
                break;
            case "hacked":
                res = R.string.jac_chat_reason_hacked;
                break;
            default:
                res = R.string.jac_report_other;
                break;
        }
        return JacStrings.get(context, res);
    }

    // ------------------------------------------------------------------
    // The gates
    // ------------------------------------------------------------------

    /**
     * From {@code ChatActivity.onBecomeFullyVisible}: a flagged chat that was
     * not confirmed yet asks now, and "back" closes it again. Not in a
     * preview, not in the scheduled, saved or search views of a chat.
     */
    public static void onChatVisible(ChatActivity fragment, TLRPC.Chat chat) {
        try {
            if (fragment == null || chat == null || fragment.isInPreviewMode() || fragment.getChatMode() != 0
                    || fragment.isReport() || ChatGuard.isApproved(chat)) {
                return;
            }
            final ChatGuard.Flag flag = ChatGuard.flagOf(chat, null);
            if (flag == null) {
                return;
            }
            final Target target = Target.of(chat, ChatGuard.noteFor(chat.id));
            ask(fragment, target, flag, MODE_OPEN, null, fragment::finishFragment);
        } catch (Throwable t) {
            FileLog.e("chat guard: open check failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * The same for a private chat: an account or a bot the owner's list or
     * Telegram marks asks before its messages are read, with "block" next to
     * "back" — in a private chat the danger writes to you, and leaving the
     * chat does not stop it.
     */
    public static void onUserVisible(ChatActivity fragment, TLRPC.User user) {
        try {
            if (fragment == null || user == null || fragment.isInPreviewMode() || fragment.getChatMode() != 0
                    || fragment.isReport() || ChatGuard.isApproved(user)) {
                return;
            }
            final ChatGuard.Flag flag = ChatGuard.flagOf(user);
            if (flag == null) {
                return;
            }
            ask(fragment, Target.of(user), flag, MODE_OPEN, null, fragment::finishFragment);
        } catch (Throwable t) {
            FileLog.e("chat guard: account check failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * From a join button. True when the guard took over: it asks twice and
     * runs {@code proceed} — the original join — only after both. False when
     * the caller should join as before.
     */
    public static boolean holdJoin(BaseFragment fragment, TLRPC.Chat chat, Runnable proceed) {
        try {
            if (fragment == null || chat == null || proceed == null || ChatGuard.isApproved(chat)) {
                return false;
            }
            final ChatGuard.Flag flag = ChatGuard.flagOf(chat, null);
            if (flag == null) {
                return false;
            }
            return ask(fragment, Target.of(chat, ChatGuard.noteFor(chat.id)), flag, MODE_JOIN, proceed, null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * From {@code LaunchActivity}, before an invite link's join sheet. True
     * when the guard took over and shows the sheet ({@code proceed}) only
     * after both confirmations.
     */
    public static boolean holdInvite(BaseFragment fragment, String inviteHash, TLRPC.ChatInvite invite, Runnable proceed) {
        try {
            if (fragment == null || proceed == null || ChatGuard.isInviteApproved(inviteHash)) {
                return false;
            }
            final ChatGuard.Flag flag = ChatGuard.flagOfInvite(inviteHash, invite);
            if (flag == null) {
                return false;
            }
            return ask(fragment, Target.of(invite, inviteHash), flag, MODE_JOIN, proceed, null);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // The menu
    // ------------------------------------------------------------------

    /**
     * The chat menu's item: "Report to Humogram", or, for a chat the owner's
     * list warns about, "Marked by mistake?". Null for no item — the guard
     * is off, or there is no chat.
     */
    public static CharSequence menuLabel(Context context, TLRPC.Chat chat) {
        try {
            if (chat == null || context == null || !ChatGuard.isOn()) {
                return null;
            }
            final ChatGuard.Flag flag = ChatGuard.flagOf(chat, null);
            return JacStrings.get(context, flag != null && flag.appealable()
                    ? R.string.jac_chatguard_menu_appeal : R.string.jac_chatguard_menu_report);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The same item in a private chat with an account or a bot. */
    public static CharSequence menuLabel(Context context, TLRPC.User user) {
        try {
            if (user == null || context == null || user.self || UserObject.isService(user.id)
                    || UserObject.isReplyUser(user) || user.id == UserObject.VERIFY || !ChatGuard.isOn()) {
                return null;
            }
            final ChatGuard.Flag flag = ChatGuard.flagOf(user);
            return JacStrings.get(context, flag != null && flag.appealable()
                    ? R.string.jac_chatguard_menu_appeal : R.string.jac_chatguard_menu_report);
        } catch (Throwable t) {
            return null;
        }
    }

    /** That item was tapped. */
    public static void onMenu(BaseFragment fragment, TLRPC.User user) {
        try {
            final Activity activity = fragment != null ? fragment.getParentActivity() : null;
            if (activity == null || user == null) {
                return;
            }
            final ChatGuard.Flag flag = ChatGuard.flagOf(user);
            final Target target = Target.of(user);
            if (flag != null && flag.appealable()) {
                ReviewRequest.confirmChatAppeal(activity, fragment, target.ref);
            } else {
                ReviewRequest.confirmChatReport(activity, fragment, target.ref);
            }
        } catch (Throwable t) {
            FileLog.e("chat guard: account menu failed: " + t.getClass().getSimpleName());
        }
    }

    /** The item was tapped. */
    public static void onMenu(BaseFragment fragment, TLRPC.Chat chat) {
        try {
            final Activity activity = fragment != null ? fragment.getParentActivity() : null;
            if (activity == null || chat == null) {
                return;
            }
            final ChatGuard.Flag flag = ChatGuard.flagOf(chat, null);
            final Target target = Target.of(chat, ChatGuard.noteFor(chat.id));
            if (flag != null && flag.appealable()) {
                ReviewRequest.confirmChatAppeal(activity, fragment, target.ref);
            } else {
                ReviewRequest.confirmChatReport(activity, fragment, target.ref);
            }
        } catch (Throwable t) {
            FileLog.e("chat guard: menu failed: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // The two confirmations
    // ------------------------------------------------------------------

    /**
     * What the dialogs are about: the chat — or the account — when it is
     * known, and what a request about it would carry.
     */
    private static final class Target {
        final TLRPC.Chat chat;
        final TLRPC.User user;
        final String invite;
        final ReviewRequest.ChatRef ref;
        final String key;

        private Target(TLRPC.Chat chat, TLRPC.User user, String invite, ReviewRequest.ChatRef ref) {
            this.chat = chat;
            this.user = user;
            this.invite = invite;
            this.ref = ref;
            this.key = user != null ? "peer:" + ChatLists.peerOfUser(user.id)
                    : chat != null ? "peer:" + ChatGuard.peerOf(chat) : "invite:" + invite;
        }

        static Target of(TLRPC.Chat chat, String invite) {
            return new Target(chat, null, invite, new ReviewRequest.ChatRef(ChatGuard.peerOf(chat),
                    ChatGuard.usernamesOf(chat), invite, chat.title,
                    ChatObject.isChannelAndNotMegaGroup(chat) ? 1 : 0));
        }

        static Target of(TLRPC.User user) {
            return new Target(null, user, null, new ReviewRequest.ChatRef(ChatLists.peerOfUser(user.id),
                    ChatGuard.usernamesOf(user), null, UserObject.getUserName(user),
                    user.bot ? ReviewRequest.ChatRef.KIND_BOT : ReviewRequest.ChatRef.KIND_ACCOUNT));
        }

        static Target of(TLRPC.ChatInvite invite, String inviteHash) {
            final TLRPC.Chat chat = invite != null ? invite.chat : null;
            if (chat != null) {
                return of(chat, inviteHash);
            }
            final boolean channel = invite != null && invite.channel && !invite.megagroup;
            final String title = invite != null ? invite.title : null;
            return new Target(null, null, inviteHash, new ReviewRequest.ChatRef(0, new ArrayList<>(), inviteHash,
                    title, channel ? 1 : 0));
        }

        boolean channel() {
            return ref.kind == 1;
        }

        /** An account or a bot: a private chat. */
        boolean account() {
            return user != null;
        }

        boolean bot() {
            return user != null && user.bot;
        }

        /** The title of step one. */
        int titleRes() {
            return bot() ? R.string.jac_userguard_title_bot : account() ? R.string.jac_userguard_title_user
                    : channel() ? R.string.jac_chatguard_title_channel : R.string.jac_chatguard_title_group;
        }

        /** "Based on users' reports, Humogram considers this … dangerous:" */
        int listRes() {
            return bot() ? R.string.jac_userguard_list_bot : account() ? R.string.jac_userguard_list_user
                    : channel() ? R.string.jac_chatguard_list_channel : R.string.jac_chatguard_list_group;
        }

        /** What it is when it has no title or username to show. */
        int kindRes() {
            return bot() ? R.string.jac_userguard_kind_bot : account() ? R.string.jac_userguard_kind_user
                    : channel() ? R.string.jac_chatguard_kind_channel : R.string.jac_chatguard_kind_group;
        }
    }

    /**
     * Step 1 of 2 — why. False when nothing could be shown, so the caller
     * goes on as upstream would: a guard that cannot ask must not leave the
     * user with a join button that does nothing.
     *
     * @param proceed what both confirmations lead to (MODE_JOIN), or null
     * @param back    what every other way out leads to (MODE_OPEN), or null
     */
    private static boolean ask(BaseFragment fragment, Target target, ChatGuard.Flag flag, int mode,
                               Runnable proceed, Runnable back) {
        final Activity activity = fragment.getParentActivity();
        if (!isAlive(activity)) {
            return false;
        }
        final AlertDialog current = asking != null ? asking.get() : null;
        if (current != null && current.isShowing() && target.key.equals(askingKey)) {
            return true;
        }
        // The list may have moved on; ask for a fresh one (throttled, never blocking).
        try {
            LinkGuard.refresh();
        } catch (Throwable ignored) {
        }

        final SpannableStringBuilder message = new SpannableStringBuilder();
        if (flag.source == ChatGuard.SOURCE_LIST) {
            message.append(JacStrings.get(activity, target.listRes()));
            final String reason = reasonLabel(activity, flag.reason);
            message.append("\n\n");
            final int at = message.length();
            message.append(reason);
            message.setSpan(new TypefaceSpan(AndroidUtilities.bold()), at, message.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (target.account()) {
            message.append(JacStrings.get(activity, flag.source == ChatGuard.SOURCE_TELEGRAM_FAKE
                    ? R.string.jac_userguard_tg_fake : R.string.jac_userguard_tg_scam));
        } else {
            message.append(JacStrings.get(activity, flag.source == ChatGuard.SOURCE_TELEGRAM_FAKE
                    ? R.string.jac_chatguard_tg_fake : R.string.jac_chatguard_tg_scam));
        }
        message.append("\n\n").append(JacStrings.get(activity, target.account()
                ? R.string.jac_userguard_advice : R.string.jac_chatguard_advice));

        final AlertDialog[] shown = new AlertDialog[1];
        final boolean[] going = {false};
        if (flag.appealable()) {
            message.append("\n\n").append(AndroidUtilities.replaceSingleTag(
                    JacStrings.get(activity, R.string.jac_chatguard_appeal_offer), () -> {
                        // The appeal is a request, not a way in: the chat stays
                        // behind its warning, and an open one closes.
                        if (shown[0] != null) {
                            shown[0].dismiss();
                        }
                        if (back != null) {
                            back.run();
                        }
                        ReviewRequest.confirmChatAppeal(activity, null, target.ref);
                    }));
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTopImage(LinkGuardUi.glyph(JacIcons.Glyph.BLOCK), LinkGuardUi.bandColor())
                .setTitle(JacStrings.get(activity, target.titleRes()))
                .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 1, 2))
                .setMessage(message)
                .setView(chatView(activity, target))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_link_back), (d, which) -> {
                    if (back != null) {
                        back.run();
                    }
                })
                .setNegativeButton(JacStrings.get(activity, R.string.jac_link_continue), (d, which) -> {
                    going[0] = true;
                    stepTwo(activity, target, flag, mode, proceed, back);
                });
        if (target.account()) {
            // A safe way out that also stops the messages: Telegram's own
            // block, the same request upstream's "Block user" sends. A
            // neutral button dismisses rather than cancels, so the chat is
            // closed here.
            final int account = fragment.getCurrentAccount();
            final TLRPC.User user = target.user;
            builder.setNeutralButton(JacStrings.get(activity, R.string.jac_userguard_block), (d, which) -> {
                going[0] = true;
                try {
                    MessagesController.getInstance(account).blockPeer(user.id);
                    BulletinFactory.global().createBanBulletin(true).show();
                } catch (Throwable t) {
                    FileLog.e("chat guard: block failed: " + t.getClass().getSimpleName());
                }
                if (back != null) {
                    back.run();
                }
            });
        }
        final AlertDialog dialog = builder.create();
        shown[0] = dialog;
        if (back != null) {
            // Back, or a tap beside the dialog, is "no" too: the chat closes.
            // Upstream's AlertDialog closes itself with cancel() after the
            // negative button too — which here is "continue" — hence the flag.
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnCancelListener(d -> {
                if (!going[0]) {
                    back.run();
                }
            });
        }
        dialog.show();
        asking = new WeakReference<>(dialog);
        askingKey = target.key;
        LinkGuardUi.colourButton(dialog, DialogInterface.BUTTON_NEGATIVE, Theme.getColor(Theme.key_text_RedBold));
        return true;
    }

    /** Step 2 of 2 — the checkbox and the hold. */
    private static void stepTwo(Activity activity, Target target, ChatGuard.Flag flag, int mode,
                                Runnable proceed, Runnable back) {
        try {
            if (!isAlive(activity)) {
                return;
            }
            final CheckBoxCell acknowledge = new CheckBoxCell(activity, 1);
            acknowledge.setMultiline(true);
            acknowledge.getTextView().getLayoutParams().width = LayoutHelper.MATCH_PARENT;
            acknowledge.getTextView().setSingleLine(false);
            acknowledge.getTextView().setMaxLines(3);
            acknowledge.getTextView().setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            acknowledge.setText(JacStrings.get(activity, R.string.jac_chatguard_confirm_checkbox), "", false, false);

            final boolean[] done = {false};
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTopImage(LinkGuardUi.glyph(JacIcons.Glyph.WARNING), LinkGuardUi.bandColor())
                    .setTitle(JacStrings.get(activity, mode == MODE_OPEN
                            ? R.string.jac_chatguard_confirm_open_title : R.string.jac_chatguard_confirm_join_title))
                    .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 2, 2))
                    .setMessage(JacStrings.get(activity, target.account()
                            ? R.string.jac_userguard_confirm_body : R.string.jac_chatguard_confirm_body))
                    .setView(acknowledge)
                    .setPositiveButton(JacStrings.get(activity, mode == MODE_OPEN
                            ? R.string.jac_chatguard_open : (target.channel()
                            ? R.string.jac_chatguard_join_channel : R.string.jac_chatguard_join_group)), (d, which) -> {
                        done[0] = true;
                        if (target.account()) {
                            ChatGuard.approve(target.user);
                        } else {
                            ChatGuard.approve(target.chat, target.invite);
                        }
                        if (proceed != null) {
                            proceed.run();
                        }
                    })
                    .setNegativeButton(JacStrings.get(activity, R.string.jac_quarantine_confirm_cancel), null)
                    .create();
            if (back != null) {
                dialog.setCanceledOnTouchOutside(false);
                // Whatever closes this without the red button closes the chat.
                dialog.setOnDismissListener(d -> {
                    if (!done[0]) {
                        back.run();
                    }
                });
            }
            dialog.show();
            asking = new WeakReference<>(dialog);
            askingKey = target.key;

            final View go = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            LinkGuardUi.colourButton(dialog, DialogInterface.BUTTON_POSITIVE, Theme.getColor(Theme.key_text_RedBold));
            final boolean[] held = {true};
            LinkGuardUi.setEnabled(go, false);
            acknowledge.setOnClickListener(v -> {
                acknowledge.setChecked(!acknowledge.isChecked(), true);
                LinkGuardUi.setEnabled(go, acknowledge.isChecked() && !held[0]);
            });
            LinkGuardUi.countDown(dialog, () -> {
                held[0] = false;
                LinkGuardUi.setEnabled(go, acknowledge.isChecked());
            });
        } catch (Throwable t) {
            FileLog.e("chat guard: step two failed: " + t.getClass().getSimpleName());
            if (back != null) {
                back.run();
            }
        }
    }

    /**
     * The chat the warning is about, in the pill {@link LinkGuardUi} shows an
     * address in: its title, and under it its public username, or "channel"
     * or "group" when it has none.
     */
    private static View chatView(Context context, Target target) {
        final LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setPadding(dp(14), dp(12), dp(14), dp(12));
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(22));
        background.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack), 0.06f));
        pill.setBackground(background);

        final TextView title = new TextView(context);
        title.setText(target.ref.title.isEmpty() ? JacStrings.get(context, target.kindRes()) : target.ref.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        pill.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final List<String> names = target.ref.usernames;
        final TextView sub = new TextView(context);
        sub.setText(!names.isEmpty() ? "@" + names.get(0) : JacStrings.get(context, target.kindRes()));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sub.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        if (!target.ref.title.isEmpty() || !names.isEmpty()) {
            pill.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(pill, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 4, 22, 9));
        return container;
    }

    private static boolean isAlive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }
}
