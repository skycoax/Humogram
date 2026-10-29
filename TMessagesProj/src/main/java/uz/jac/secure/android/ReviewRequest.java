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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * "Send for review": the user believes a red link's site, or an app the
 * device scanner flagged, is genuine — a new government service, a bank's new
 * app — and asks the owner to look.
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
        try {
            BulletinFactory factory = fragment != null ? BulletinFactory.of(fragment) : BulletinFactory.global();
            if (factory == null) {
                return;
            }
            switch (result) {
                case RESULT_SENT:
                    factory.createSimpleBulletin(R.raw.contact_check,
                            JacStrings.get(activity, R.string.jac_review_sent)).show();
                    break;
                case RESULT_ALREADY:
                    factory.createSimpleBulletin(R.raw.contact_check,
                            JacStrings.get(activity, R.string.jac_review_already)).show();
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
