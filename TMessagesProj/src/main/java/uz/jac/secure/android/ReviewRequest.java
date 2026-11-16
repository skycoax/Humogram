package uz.jac.secure.android;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * "Send for review": the user believes a red link's site, or an app the
 * device scanner flagged, is genuine — a new government service, a bank's new
 * app — and asks the owner to look. And the other direction, "report": a site
 * the guard lets through, or a Telegram channel or group, is a scam; and
 * "appeal": a blocked site or a warned-about chat is not.
 *
 * <h3>Reports and appeals</h3>
 *
 * A site report carries the site's name and the reason picked, nothing else.
 * A chat report or appeal carries what identifies the chat — its id, its
 * public usernames, its title, whether it is a channel or a group — the reason
 * picked, and, when the user reached it by an invite link, that link: without
 * it the owner cannot see a private group to judge it. A report about an
 * account or a bot carries its id, its public usernames and the name its
 * profile shows, and whether it is a bot. The dialog says so before anything
 * is sent. Never the user's messages, never who they are.
 *
 * <h3>What goes, and when</h3>
 *
 * Only after a tap and a dialog that says exactly what will be sent. For a
 * site, its name (the host, in its ASCII form) — never the link, its path or
 * query, or the chat it was in. For an app, its package, the SHA-256 of its
 * signing certificates, its name and its version — never anything about the
 * user or the other apps. One HTTPS POST to {@code jac_review_requests_url},
 * the host the lists come from, with the same {@code Humogram/<versionCode>}
 * user agent the list download sends and nothing else: no cookie, no token,
 * no account, no device id. The server keeps no address either.
 *
 * <h3>What comes of it</h3>
 *
 * Nothing on the phone. The request joins a queue on the owner's admin page;
 * if the owner agrees, the next signed list carries the site or the app, and
 * every phone picks it up the way it picks up any list ({@link LinkGuard}).
 * The certificates are what lets the owner vouch for this publisher's app and
 * not for every app that borrows its package name.
 */
final class ReviewRequest {

    private static final int RESULT_SENT = 0, RESULT_ALREADY = 1, RESULT_BUSY = 2, RESULT_FAILED = 3;

    private static final int CONNECT_TIMEOUT_MS = 8 * 1000;
    private static final int READ_TIMEOUT_MS = 10 * 1000;
    /** The server's answer is a few dozen bytes; anything much larger is not it. */
    private static final int MAX_ANSWER_BYTES = 4 * 1024;

    /** One request at a time: a second tap while the first is on its way does nothing. */
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);

    private ReviewRequest() {
    }

    /**
     * Ask whether to send this site, then send it. UI thread.
     *
     * @param asciiHost the host as it goes on the wire (punycode); what is sent
     * @param displayHost the host as the user reads it; what the dialog names
     */
    static void confirmSite(Activity activity, String asciiHost, String displayHost) {
        if (activity == null || asciiHost == null || asciiHost.isEmpty()) {
            return;
        }
        final String shown = displayHost != null && !displayHost.isEmpty() ? displayHost : asciiHost;
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(JacStrings.get(activity, R.string.jac_review_title))
                .setMessage(JacStrings.get(activity, R.string.jac_review_site_body, shown))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_review_send), (d, which) -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("kind", "site");
                        body.put("host", asciiHost);
                        send(activity.getApplicationContext(), body, result -> notify(activity, null, result));
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.show();
    }

    /**
     * A site on the block list that the user believes is blocked by mistake:
     * the same request as {@link #confirmSite} — "look at this site" — with
     * the words for an appeal. UI thread.
     */
    static void confirmSiteAppeal(Activity activity, String asciiHost, String displayHost) {
        if (activity == null || asciiHost == null || asciiHost.isEmpty()) {
            return;
        }
        final String shown = displayHost != null && !displayHost.isEmpty() ? displayHost : asciiHost;
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(JacStrings.get(activity, R.string.jac_appeal_title))
                .setMessage(JacStrings.get(activity, R.string.jac_appeal_site_body, shown))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_review_send), (d, which) -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("kind", "site");
                        body.put("host", asciiHost);
                        send(activity.getApplicationContext(), body,
                                result -> notify(activity, null, result, R.string.jac_review_sent, R.string.jac_appeal_site_already));
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.show();
    }

    /** The reasons a site can be reported for, as the server names them, and the words for each. */
    private static final String[] SITE_REASONS = {"phishing", "malware", "scam", "other"};
    private static final int[] SITE_REASON_LABELS = {
            R.string.jac_report_site_phishing, R.string.jac_report_site_malware,
            R.string.jac_report_site_scam, R.string.jac_report_other};

    /**
     * Report a site the guard lets through — trusted or merely unknown — as
     * dangerous: the reason first, then what will be sent. UI thread.
     */
    static void confirmSiteReport(Activity activity, BaseFragment fragment, String asciiHost, String displayHost) {
        if (activity == null || asciiHost == null || asciiHost.isEmpty()) {
            return;
        }
        final String shown = displayHost != null && !displayHost.isEmpty() ? displayHost : asciiHost;
        final CharSequence[] items = new CharSequence[SITE_REASONS.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = JacStrings.get(activity, SITE_REASON_LABELS[i]);
        }
        final AlertDialog pick = new AlertDialog.Builder(activity)
                .setTitle(JacStrings.get(activity, R.string.jac_report_site_title, shown))
                .setItems(items, (d, which) -> {
                    if (which < 0 || which >= SITE_REASONS.length) {
                        return;
                    }
                    final String reason = SITE_REASONS[which];
                    final AlertDialog confirm = new AlertDialog.Builder(activity)
                            .setTitle(JacStrings.get(activity, R.string.jac_report_confirm_title))
                            .setMessage(JacStrings.get(activity, R.string.jac_report_site_body, shown, items[which]))
                            .setPositiveButton(JacStrings.get(activity, R.string.jac_review_send), (d2, w2) -> {
                                try {
                                    JSONObject body = new JSONObject();
                                    body.put("kind", "site_report");
                                    body.put("host", asciiHost);
                                    body.put("reason", reason);
                                    send(activity.getApplicationContext(), body, result -> notify(activity, fragment,
                                            result, R.string.jac_report_sent, R.string.jac_report_site_already));
                                } catch (Throwable ignored) {
                                }
                            })
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .create();
                    show(fragment, confirm);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        show(fragment, pick);
    }

    /** What a chat report or appeal names — a channel or group, or an account or bot. */
    static final class ChatRef {
        static final int KIND_UNKNOWN = -1, KIND_GROUP = 0, KIND_CHANNEL = 1, KIND_ACCOUNT = 2, KIND_BOT = 3;

        /**
         * The Bot API id — negative for a channel or group, the account's own
         * (positive) id for an account or bot — or 0 when only an invite link
         * is known (a group not joined yet).
         */
        final long peer;
        final List<String> usernames;
        /** The invite link's hash the user reached it by, or null. */
        final String invite;
        /** A chat's title, or an account's name as its profile shows it. */
        final String title;
        /** {@code KIND_*}: -1 not known (an invite link not opened yet). */
        final int kind;

        ChatRef(long peer, List<String> usernames, String invite, String title, int kind) {
            this.peer = peer;
            this.usernames = usernames != null ? usernames : new ArrayList<>();
            this.invite = invite != null && !invite.isEmpty() ? invite : null;
            this.title = title != null ? title : "";
            this.kind = kind;
        }

        boolean account() {
            return kind == KIND_ACCOUNT || kind == KIND_BOT;
        }

        /** What the server calls it: "channel", "group", "user", "bot", or "" when not known. */
        String type() {
            switch (kind) {
                case KIND_CHANNEL:
                    return "channel";
                case KIND_GROUP:
                    return "group";
                case KIND_ACCOUNT:
                    return "user";
                case KIND_BOT:
                    return "bot";
                default:
                    return "";
            }
        }

        /** The chat as a sentence names it: its title in quotes, or "this group or channel" / "this account". */
        String name(Context context) {
            return title.isEmpty() ? JacStrings.get(context, account()
                    ? R.string.jac_report_user_unnamed : R.string.jac_report_chat_unnamed)
                    : JacStrings.get(context, R.string.jac_report_chat_named, title);
        }

        /** The words for "what is sent", in the user's language. */
        String sentList(Context context) {
            final StringBuilder out = new StringBuilder();
            if (!usernames.isEmpty()) {
                out.append(JacStrings.get(context, R.string.jac_report_sends_username, "@" + usernames.get(0)));
            }
            if (peer != 0) {
                append(out, JacStrings.get(context, R.string.jac_report_sends_id));
            }
            if (!title.isEmpty()) {
                append(out, JacStrings.get(context, account()
                        ? R.string.jac_report_sends_name : R.string.jac_report_sends_title));
            }
            if (invite != null) {
                append(out, JacStrings.get(context, R.string.jac_report_sends_invite));
            }
            return out.toString();
        }

        /**
         * A chat named only by an invite link in a message — t.me/+…,
         * t.me/joinchat/…, tg://join?invite=… — or null when {@code url} is
         * not one. Such a link always leads to a group or channel, never to a
         * person, so it can be reported without opening it. A t.me/name link
         * can lead to a person, and is not taken here: a chat is reported from
         * its own menu, where it is known to be one.
         */
        static ChatRef ofInviteLink(String url) {
            try {
                final android.net.Uri uri = android.net.Uri.parse(url.trim());
                final String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(java.util.Locale.ROOT) : "";
                final String host = uri.getHost() != null ? uri.getHost().toLowerCase(java.util.Locale.ROOT) : "";
                String hash = null;
                if (scheme.equals("tg") && host.equals("join")) {
                    hash = uri.getQueryParameter("invite");
                } else if (host.equals("t.me") || host.equals("www.t.me") || host.equals("telegram.me")
                        || host.equals("telegram.dog")) {
                    final List<String> segments = uri.getPathSegments();
                    if (!segments.isEmpty() && segments.get(0).startsWith("+")) {
                        hash = segments.get(0).substring(1);
                    } else if (segments.size() >= 2 && segments.get(0).equalsIgnoreCase("joinchat")) {
                        hash = segments.get(1);
                    }
                }
                if (hash == null || !hash.matches("[A-Za-z0-9_-]{1,64}")) {
                    return null;
                }
                return new ChatRef(0, new ArrayList<>(), hash, "", -1);
            } catch (Throwable t) {
                return null;
            }
        }

        private static void append(StringBuilder out, String part) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(part);
        }
    }

    /**
     * Report a channel, group, account or bot as a scam: the reason first,
     * then what will be sent. UI thread.
     */
    static void confirmChatReport(Activity activity, BaseFragment fragment, ChatRef chat) {
        if (activity == null || chat == null) {
            return;
        }
        final String[] reasons = chat.account() ? ChatGuardUi.USER_REASONS : ChatGuardUi.REASONS;
        final CharSequence[] items = new CharSequence[reasons.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = ChatGuardUi.reasonLabel(activity, reasons[i]);
        }
        final int titleRes;
        switch (chat.kind) {
            case ChatRef.KIND_CHANNEL:
                titleRes = R.string.jac_report_channel_title;
                break;
            case ChatRef.KIND_GROUP:
                titleRes = R.string.jac_report_group_title;
                break;
            case ChatRef.KIND_ACCOUNT:
                titleRes = R.string.jac_report_user_title;
                break;
            case ChatRef.KIND_BOT:
                titleRes = R.string.jac_report_bot_title;
                break;
            default:
                titleRes = R.string.jac_report_chat_title;
                break;
        }
        final AlertDialog pick = new AlertDialog.Builder(activity)
                .setTitle(JacStrings.get(activity, titleRes))
                .setItems(items, (d, which) -> {
                    if (which < 0 || which >= reasons.length) {
                        return;
                    }
                    String body = JacStrings.get(activity, chat.account()
                                    ? R.string.jac_report_user_body : R.string.jac_report_chat_body,
                            chat.name(activity), items[which], chat.sentList(activity));
                    if (chat.kind == ChatRef.KIND_BOT) {
                        // Upstream offers "Report" on a bot's messages.
                        body += "\n\n" + JacStrings.get(activity, R.string.jac_report_bot_telegram);
                    } else if (chat.account()) {
                        // A private chat with a person has no "Report" of
                        // Telegram's own; impersonators go to @NoToScam (Telegram FAQ).
                        body += "\n\n" + JacStrings.get(activity, R.string.jac_report_user_telegram);
                    } else if (chat.peer != 0) {
                        // Opened from the chat's menu, where Telegram's own report sits too.
                        body += "\n\n" + JacStrings.get(activity, R.string.jac_report_chat_telegram);
                    }
                    final AlertDialog confirm = new AlertDialog.Builder(activity)
                            .setTitle(JacStrings.get(activity, R.string.jac_report_confirm_title))
                            .setMessage(body)
                            .setPositiveButton(JacStrings.get(activity, R.string.jac_review_send), (d2, w2) ->
                                    sendChat(activity, fragment, chat, "chat", reasons[which]))
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .create();
                    show(fragment, confirm);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        show(fragment, pick);
    }

    /** A chat warned about by mistake: what will be sent, then send. UI thread. */
    static void confirmChatAppeal(Activity activity, BaseFragment fragment, ChatRef chat) {
        if (activity == null || chat == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(JacStrings.get(activity, R.string.jac_appeal_title))
                .setMessage(JacStrings.get(activity, R.string.jac_appeal_chat_body, chat.name(activity), chat.sentList(activity)))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_review_send), (d, which) ->
                        sendChat(activity, fragment, chat, "chat_appeal", null))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        show(fragment, dialog);
    }

    private static void sendChat(Activity activity, BaseFragment fragment, ChatRef chat, String kind, String reason) {
        try {
            JSONObject body = new JSONObject();
            body.put("kind", kind);
            if (chat.peer != 0) {
                body.put("peer", chat.peer);
            }
            JSONArray names = new JSONArray();
            for (String u : chat.usernames) {
                if (names.length() < 10) {
                    names.put(u);
                }
            }
            body.put("usernames", names);
            if (chat.invite != null) {
                body.put("invite", chat.invite);
            }
            body.put("title", chat.title.length() > 128 ? chat.title.substring(0, 128) : chat.title);
            body.put("type", chat.type());
            if (reason != null) {
                body.put("reason", reason);
            }
            final boolean appeal = "chat_appeal".equals(kind);
            send(activity.getApplicationContext(), body, result -> notify(activity, fragment, result,
                    appeal ? R.string.jac_review_sent : R.string.jac_report_sent,
                    appeal ? R.string.jac_appeal_chat_already : R.string.jac_report_chat_already));
        } catch (Throwable ignored) {
        }
    }

    /** On the fragment when there is one, so it is dismissed with it; otherwise on its own. */
    private static void show(BaseFragment fragment, AlertDialog dialog) {
        if (fragment != null && fragment.getParentActivity() != null) {
            fragment.showDialog(dialog);
        } else {
            dialog.show();
        }
    }

    /**
     * Ask whether to send this installed app, then send it — with its signing
     * certificates, read when the request is sent. UI thread.
     */
    static void confirmApp(BaseFragment fragment, String packageName, String label, String versionName) {
        final Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null || packageName == null) {
            return;
        }
        final String name = label != null && !label.isEmpty() ? label : packageName;
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(JacStrings.get(activity, R.string.jac_review_title))
                .setMessage(JacStrings.get(activity, R.string.jac_review_app_body, name))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_review_send), (d, which) -> {
                    final Context app = activity.getApplicationContext();
                    send(app, () -> {
                        Set<String> certs = DeviceAppCollector.signingDigestsOf(app, packageName);
                        if (certs.isEmpty()) {
                            // Gone since the scan, or unreadable: there is no
                            // signer for the owner to vouch for.
                            return null;
                        }
                        JSONObject body = new JSONObject();
                        body.put("kind", "app");
                        body.put("package", packageName);
                        body.put("cert_sha256", new JSONArray(certs));
                        body.put("name", name);
                        body.put("version", versionName != null ? versionName : "");
                        return body;
                    }, result -> notify(activity, fragment, result));
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        fragment.showDialog(dialog);
    }

    // ------------------------------------------------------------------
    // sending
    // ------------------------------------------------------------------

    private interface Body {
        /** The request body, built off the main thread; null when there is nothing to send. */
        JSONObject build() throws Exception;
    }

    private interface Done {
        /** UI thread. */
        void onResult(int result);
    }

    private static void send(Context context, JSONObject body, Done done) {
        send(context, () -> body, done);
    }

    private static void send(Context context, Body body, Done done) {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            new Thread(() -> {
                int result = RESULT_FAILED;
                try {
                    JSONObject built = body.build();
                    if (built != null) {
                        result = post(context, built);
                    }
                } catch (Throwable ignored) {
                } finally {
                    inFlight.set(false);
                }
                final int answer = result;
                AndroidUtilities.runOnUIThread(() -> done.onResult(answer));
            }, "jac-review").start();
        } catch (Throwable t) {
            inFlight.set(false);
        }
    }

    /** One POST; a {@code RESULT_*}. Never throws. */
    private static int post(Context context, JSONObject body) {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(context.getString(R.string.jac_review_requests_url).trim());
            URLConnection opened = "https".equalsIgnoreCase(url.getProtocol()) ? url.openConnection() : null;
            if (!(opened instanceof HttpsURLConnection)) {
                // The same refusal as the list download: never in clear text.
                return RESULT_FAILED;
            }
            connection = (HttpsURLConnection) opened;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                // The list host's root is missing from these system stores; see LinkGuardTrust.
                SSLSocketFactory ownTrust = LinkGuardTrust.socketFactory();
                if (ownTrust != null) {
                    connection.setSSLSocketFactory(ownTrust);
                }
            }
            byte[] bytes = body.toString().getBytes("UTF-8");
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Humogram/" + LinkGuard.versionCode(context));
            OutputStream out = connection.getOutputStream();
            try {
                out.write(bytes);
            } finally {
                out.close();
            }

            int code = connection.getResponseCode();
            if (code == HttpsURLConnection.HTTP_OK) {
                return "already".equals(statusOf(connection)) ? RESULT_ALREADY : RESULT_SENT;
            }
            if (code == 429 || code == HttpsURLConnection.HTTP_UNAVAILABLE) {
                return RESULT_BUSY;
            }
            return RESULT_FAILED;
        } catch (Throwable t) {
            return RESULT_FAILED;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** The answer's {@code status}: "queued" or "already". Null when it cannot be read. */
    private static String statusOf(HttpsURLConnection connection) {
        try {
            InputStream in = connection.getInputStream();
            try {
                ByteArrayOutputStream answer = new ByteArrayOutputStream(128);
                byte[] buffer = new byte[512];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    answer.write(buffer, 0, read);
                    if (answer.size() > MAX_ANSWER_BYTES) {
                        return null;
                    }
                }
                return new JSONObject(answer.toString("UTF-8")).optString("status", null);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** The outcome, in upstream's bulletin: on the fragment when there is one, otherwise on top of the app. */
    private static void notify(Activity activity, BaseFragment fragment, int result) {
        notify(activity, fragment, result, R.string.jac_review_sent, R.string.jac_review_already);
    }

    private static void notify(Activity activity, BaseFragment fragment, int result, int sentRes, int alreadyRes) {
        try {
            BulletinFactory factory = fragment != null && fragment.getParentActivity() != null
                    ? BulletinFactory.of(fragment) : BulletinFactory.global();
            if (factory == null) {
                return;
            }
            switch (result) {
                case RESULT_SENT:
                    factory.createSimpleBulletin(R.raw.contact_check,
                            JacStrings.get(activity, sentRes)).show();
                    break;
                case RESULT_ALREADY:
                    factory.createSimpleBulletin(R.raw.contact_check,
                            JacStrings.get(activity, alreadyRes)).show();
                    break;
                case RESULT_BUSY:
                    factory.createErrorBulletin(JacStrings.get(activity, R.string.jac_review_busy)).show();
                    break;
                default:
                    factory.createErrorBulletin(JacStrings.get(activity, R.string.jac_review_failed)).show();
                    break;
            }
        } catch (Throwable ignored) {
        }
    }
}
