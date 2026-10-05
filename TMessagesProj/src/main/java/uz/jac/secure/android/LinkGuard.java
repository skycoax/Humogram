package uz.jac.secure.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.ForegroundDetector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;

import uz.jac.secure.core.engine.EngineFactory;
import uz.jac.secure.core.link.DomainLists;
import uz.jac.secure.core.link.LinkDecision;
import uz.jac.secure.core.link.LinkPolicy;
import uz.jac.secure.core.link.LinkReason;
import uz.jac.secure.core.link.LinkTier;
import uz.jac.secure.core.link.SignedLists;

/**
 * The link guard's engine: which tier a link belongs to, and keeping the
 * owner's site lists current.
 *
 * <p>Every web link is one of three things. Its site is on the trusted list
 * and it behaves as it always did; or it is on the blocked list, or imitates a
 * brand, and it is dangerous; or — everything else — it is simply unknown.
 * Unknown is not a polite word for fine: an unknown link is drawn red and takes
 * two confirmations to open, exactly because a phishing page registered this
 * morning is on nobody's blocklist yet. {@link LinkGuardUi} does the drawing
 * and the asking; this class only answers "which tier" and keeps the answer
 * up to date.
 *
 * <h3>The list is pulled, the matching is local</h3>
 *
 * The app downloads one public, signed file and matches links against it on
 * the phone. No link, no host, no message and no identifier is ever sent
 * anywhere: the request is the same GET for every user, carries no query, no
 * cookie and no token, and the policy itself is built with no backend object
 * at all (see {@code EngineFactory.linkPolicyFor}), so there is nothing a
 * later edit could turn into a lookup. For the same reason nothing in this
 * class logs a URL or a host, and the scanner API's base URL string is not
 * read here.
 *
 * <h3>What a downloaded list has to get past</h3>
 *
 * TLS to the list host, with user-installed CAs not trusted
 * ({@code jac_network_security_config.xml}; before Android 7.1.1, where the
 * system store lacks the host's root, {@link LinkGuardTrust}); then an ECDSA
 * signature over the payload, checked against keys compiled into the app — the
 * server's TLS key is on the server, the signing key's backup is not, so a
 * taken-over server still cannot mint a list; then the document's own
 * validation in the core; then the version, which may never go down — and,
 * because of that, may not come from the far future either
 * ({@link #MAX_VERSION_AHEAD_S}). A list that fails any of these changes
 * nothing: the one already in force stays in force, and with no list at all
 * the bundled one does — under which an unknown site is still unknown. Being
 * offline therefore never makes a link look safer than it did.
 *
 * <h3>Threads</h3>
 *
 * {@link #tierOf} is called from a text span's {@code updateDrawState}: during
 * layout and draw, thousands of times a second while a chat scrolls, and not
 * always on the UI thread. It takes no lock, allocates nothing once a URL has
 * been seen, and cannot throw. Disk and network belong to one worker thread,
 * which is also what makes a refresh single-file with everything else that
 * touches the store. The redraw is posted to the UI thread.
 *
 * <h3>When the list is refreshed</h3>
 *
 * No service, no alarm, no job. A few seconds after the process starts; when
 * the app comes to the foreground; every five minutes while it stays there;
 * and when the user taps a link ({@link LinkGuardUi} asks). All of them are the
 * same conditional GET, which the server answers with a 304 and no body when
 * nothing changed, and none runs more often than the minimum interval.
 */
public final class LinkGuard {

    public static final int TIER_NOT_WEB = 0, TIER_TRUSTED = 1, TIER_UNKNOWN = 2, TIER_DANGEROUS = 3;

    public static final int RESULT_NEVER = 0, RESULT_OK = 1, RESULT_NOT_MODIFIED = 2, RESULT_NETWORK_ERROR = 3,
            RESULT_BAD_SIGNATURE = 4, RESULT_BAD_DOCUMENT = 5, RESULT_STALE_VERSION = 6, RESULT_HTTP_ERROR = 7,
            RESULT_TLS_ERROR = 8;

    /** Bundled default lists; a byte-identical copy of shared/data/link-default-allowlist.json. */
    private static final String BUNDLED_ASSET = "link-default-allowlist.json";

    private static final String LOG_TAG = "jac";

    // ------------------------------------------------------------------
    // refresh schedule
    // ------------------------------------------------------------------

    /** How often the list is re-checked while the app stays in the foreground. */
    private static final long TICK_MS = 5 * 60 * 1000L;
    /** Minimum gap between automatic checks (foreground, tap, start-up) after one that got an answer. */
    private static final long MIN_INTERVAL_MS = TICK_MS;
    /**
     * The floor as seen by the tick, which must be lower. Gaps are measured
     * from the moment the previous attempt <em>finished</em>, and a tick fires
     * a jittered 4.5 to 5.5 minutes after the previous tick <em>started</em>
     * one — so on a slow network a tick arrives well under five minutes after
     * the last attempt ended. Held to {@link #MIN_INTERVAL_MS} it would be
     * refused every other time and the real period would double. All the tick
     * needs protecting from is running right after a check that a tap or a
     * return to the foreground has just made.
     */
    private static final long TICK_FLOOR_MS = TICK_MS / 2;
    /**
     * Minimum gap after a check that failed. Shorter than the normal floor so
     * that a phone which was offline a minute ago picks the list up on the
     * next tap, but not zero: a server that is down must not be asked again by
     * every tap of every user.
     */
    private static final long RETRY_FLOOR_MS = 60 * 1000L;
    /** Process start is busy and the radio may not be up; the first check waits this long. */
    private static final long STARTUP_DELAY_MS = 4 * 1000L;

    private static final int CONNECT_TIMEOUT_MS = 8 * 1000;
    private static final int READ_TIMEOUT_MS = 10 * 1000;
    /**
     * The read timeout bounds one read, not the response: a server feeding a
     * byte every nine seconds would otherwise hold the worker for hours.
     */
    private static final long BODY_BUDGET_MS = 30 * 1000L;

    /**
     * How far ahead of this phone's clock a list's version may be, in seconds.
     *
     * <p>Versions are publication times — the service numbers them with its
     * clock, in unix seconds — and the version in force may never go down.
     * Without a ceiling those two rules make one bad list permanent: a single
     * envelope signed with a version centuries ahead would turn every honest
     * list after it into "an older one", on every phone that fetched it,
     * until an app update. That is exactly the day the backup key is for, and
     * a list signed with it would be refused like any other. A week is far
     * more than a phone's clock is ever wrong by, and it bounds that lockout
     * to a week. The service refuses to number a list beyond the same limit;
     * the two must stay equal.
     */
    private static final long MAX_VERSION_AHEAD_S = 7L * 24 * 3600;

    /** {@code ForegroundDetector} does not exist yet when we are installed; see {@link #registerForeground}. */
    private static final int REGISTER_ATTEMPTS = 20;
    private static final long REGISTER_RETRY_MS = 500L;

    private static final int MODE_AUTO = 0, MODE_TICK = 1;

    /** "No attempt yet" for {@link #lastAttemptAt}; not a time, so never subtract from it. */
    private static final long NEVER = Long.MIN_VALUE;

    // ------------------------------------------------------------------
    // tier memo
    // ------------------------------------------------------------------

    /**
     * How many URLs the memo remembers. A long chat shows a few hundred
     * distinct links; past the cap the memo starts again empty, which costs a
     * few microseconds per visible link and nothing else.
     */
    private static final int MEMO_MAX_ENTRIES = 2048;
    /**
     * The longest URL the memo will hold: a whole message's worth, so that
     * every link a chat can carry is remembered. This memo is the only cache
     * there is — {@link LinkGuardUi} keeps nothing on the span — so a link
     * left out of it is parsed again at every draw. Anything longer did not
     * come out of a message and is classified each time.
     */
    private static final int MEMO_MAX_KEY_CHARS = 4096;
    /**
     * The memo keeps its keys alive, so it is bounded by their total length
     * as well as their number: two thousand tracking URLs of a few kilobytes
     * each would otherwise be megabytes pinned for a lookup that takes
     * microseconds. 512 K chars is about one megabyte.
     */
    private static final int MEMO_MAX_TOTAL_CHARS = 512 * 1024;

    /** Boxed once, so a memo hit or store never allocates whatever the VM's Integer cache does. */
    private static final Integer[] BOXED = {
            Integer.valueOf(TIER_NOT_WEB), Integer.valueOf(TIER_TRUSTED),
            Integer.valueOf(TIER_UNKNOWN), Integer.valueOf(TIER_DANGEROUS)};

    /**
     * URL string to tier, valid for exactly one state of the lists and the
     * toggle. Never edited to follow a change: a change swaps in a new, empty
     * memo ({@link #bumpGeneration}), so a draw pass that was mid-lookup
     * against the old lists writes its answer into a map nobody reads again.
     *
     * <p>Keys are the URL strings the spans already hold. They live in memory
     * only, are dropped when the app goes to the background, and are never
     * written or logged.
     */
    private static final class Memo {
        final ConcurrentHashMap<String, Integer> tiers = new ConcurrentHashMap<>(256);
        /** Entries and key characters stored since the map was last emptied; upper bounds, not exact. */
        final AtomicInteger size = new AtomicInteger();
        final AtomicInteger chars = new AtomicInteger();
    }

    private static volatile Memo memo = new Memo();

    /**
     * Starts at 1 so that a span's own cached generation, which starts life
     * as 0, can never match before the span has asked once.
     */
    private static final AtomicInteger generation = new AtomicInteger(1);

    // ------------------------------------------------------------------
    // state
    // ------------------------------------------------------------------

    /** Non-null once installed; the single publication point that turns the guard on. */
    private static volatile LinkPolicy policy;
    private static volatile Context appContext;
    private static volatile EngineFactory.ReferenceData referenceData;

    /**
     * Our own handler rather than {@code AndroidUtilities.runOnUIThread}: that
     * one silently drops the runnable until {@code ApplicationLoader} has
     * created its handler, which happens after we are installed.
     */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * One thread for everything that touches the disk or the network. One,
     * because the store is not thread-safe and because two list downloads at
     * once would be a bug, not a speed-up. It is allowed to die when idle: the
     * guard needs it for a second every five minutes.
     */
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), runnable -> {
        Thread thread = new Thread(runnable, "jac-linkguard");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    static {
        WORKER.allowCoreThreadTimeOut(true);
    }

    /** Single-flight: at most one refresh is queued or running. */
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);

    /** {@code elapsedRealtime} when the last attempt finished, or {@link #NEVER}. */
    private static volatile long lastAttemptAt = NEVER;
    private static volatile boolean lastAttemptOk;

    /** Written on the UI thread by the foreground listener. */
    private static volatile boolean foreground;

    // Everything below is read and written by worker tasks only, one at a
    // time. Volatile all the same: the worker thread is allowed to die when
    // idle, so two consecutive tasks may run on two different threads.
    private static volatile boolean loaded;
    private static volatile LinkGuardStore store;
    private static volatile List<String> publicKeys;
    private static volatile String userAgent;
    /** ETag of the envelope in force; null means the next GET is unconditional. */
    private static volatile String etag;
    private static volatile long checkedAt;
    private static volatile long changedAt;
    private static volatile int lastResult = RESULT_NEVER;
    /** Highest version ever accepted; outlives a lost cache file (see {@link LinkGuardStore}). */
    private static volatile long versionFloor;
    /** What {@code meta.json} currently says, so an unchanged record is not rewritten. */
    private static volatile String writtenMeta;

    private LinkGuard() {
    }

    // ------------------------------------------------------------------
    // install
    // ------------------------------------------------------------------

    /**
     * From {@link ScannerBootstrap#install}. Builds the policy from the bundled
     * list, then leaves the rest — the cached signed list, the first refresh,
     * the foreground hook — to the worker and the main looper. Never throws.
     *
     * <p>The bundled list is parsed here, on the caller's thread, and that is
     * deliberate: it is a small file, and it is what makes the guard exist
     * before the first chat can be drawn or the first link tapped. Built on
     * the worker instead, there would be a moment after every process start —
     * the moment a notification tap lands in — when links open unchecked.
     * What may arrive late is only the owner's list, and until it does a site
     * it would have trusted is merely asked about.
     */
    static void install(Context context, EngineFactory.ReferenceData data) {
        try {
            if (policy != null || context == null || data == null) {
                return;
            }
            Context app = context.getApplicationContext();
            if (app == null) {
                app = context;
            }

            String bundled = null;
            try {
                bundled = ScannerBootstrap.asset(app.getAssets(), BUNDLED_ASSET);
            } catch (Throwable t) {
                // The policy still builds: brand domains stay trusted and every
                // other site is unknown. Loud in the log, because a build that
                // lost this asset paints nearly every link red.
                android.util.Log.e(LOG_TAG, "link guard: bundled list unavailable: " + t.getClass().getSimpleName());
            }
            LinkPolicy built = EngineFactory.linkPolicyFor(data, bundled);

            appContext = app;
            referenceData = data;
            policy = built;
            bumpGeneration();

            WORKER.execute(() -> {
                try {
                    ensureLoaded();
                } catch (Throwable t) {
                    android.util.Log.w(LOG_TAG, "link guard: cached list not loaded: " + t.getClass().getSimpleName());
                }
            });
            registerForeground(0);
            MAIN.postDelayed(() -> startRefresh(MODE_AUTO), STARTUP_DELAY_MS);
        } catch (Throwable t) {
            // No guard is the messenger the user had before; a crash here is
            // no messenger at all. The class only: a message could quote data.
            android.util.Log.e(LOG_TAG, "link guard failed to start: " + t.getClass().getSimpleName());
        }
    }

    public static boolean isInstalled() {
        return policy != null;
    }

    /** Feature toggle ({@link HumogramConfig#isLinkGuard}, default on) AND installed. */
    public static boolean isActive() {
        return policy != null && enabled();
    }

    private static boolean enabled() {
        Context context = appContext;
        return context != null && HumogramConfig.isLinkGuard(context);
    }

    // ------------------------------------------------------------------
    // tiers
    // ------------------------------------------------------------------

    /**
     * The colouring fast path: the tier of whatever string a URL span carries.
     *
     * <p>{@link #TIER_TRUSTED} — "leave it as it is" — when the guard is off or
     * not installed, and also if anything at all goes wrong: this runs inside a
     * draw pass, where the only acceptable failure is a link that is not
     * coloured. The tap is judged separately by {@link #decide}, which does not
     * share that leniency.
     *
     * <p>Accepts anything a URL span carries. What is and is not a web link
     * is the core's decision ({@code LinkPolicy.tierOf}), so this always
     * agrees with {@link #decide}: {@code tg:}, {@code tel:}, {@code mailto:},
     * mentions, hashtags and commands are {@link #TIER_NOT_WEB}.
     *
     * <p>Safe from any thread. A URL that has been seen since the last list
     * change costs one lock-free map read and no allocation.
     *
     * <p>A caller that caches the answer next to {@link #generation()} must
     * read the generation <em>before</em> calling this. Read afterwards, a
     * list change landing between the two calls would file the old tier under
     * the new generation, and the link would keep the wrong colour until the
     * next change.
     */
    public static int tierOf(String url) {
        try {
            LinkPolicy current = policy;
            if (current == null || !enabled()) {
                return TIER_TRUSTED;
            }
            if (url == null || isInTextToken(url)) {
                return TIER_NOT_WEB;
            }
            Memo m = memo;
            Integer known = m.tiers.get(url);
            if (known != null) {
                return known.intValue();
            }
            int tier = toInt(current.tierOf(url));
            int length = url.length();
            if (length <= MEMO_MAX_KEY_CHARS) {
                if (m.size.incrementAndGet() > MEMO_MAX_ENTRIES
                        || m.chars.addAndGet(length) > MEMO_MAX_TOTAL_CHARS) {
                    // Full. Emptying it is cruder than evicting the oldest,
                    // but needs no bookkeeping on the read path, and refilling
                    // costs microseconds per link actually on screen.
                    m.tiers.clear();
                    m.size.set(1);
                    m.chars.set(length);
                }
                m.tiers.put(url, BOXED[tier]);
            }
            return tier;
        } catch (Throwable t) {
            return TIER_TRUSTED;
        }
    }

    /**
     * Telegram's own in-text tokens — {@code @mention}, {@code #tag},
     * {@code $TICKER}, {@code /command} — which travel in the same spans as
     * links and which most chats draw far more of than links.
     *
     * <p>This is a shortcut, not a second opinion. It recognises a strict
     * subset of what the core itself answers "not a web link" for
     * ({@code LinkPolicy}'s non-link token rule): a marker followed by ASCII
     * letters, digits and underscores, or a hash followed by anything without
     * whitespace. Everything else, however odd, goes to the core, so that the
     * colour of a link and the question asked when it is tapped always come
     * from the same judgement. What the shortcut buys is that mentions never
     * take up room in the memo and never reach a regular expression.
     *
     * <p>No allocation.
     */
    static boolean isInTextToken(String s) {
        int n = s.length();
        if (n == 0) {
            return false;
        }
        char first = s.charAt(0);
        if (first == '#') {
            for (int i = 1; i < n; i++) {
                if (s.charAt(i) <= ' ') {
                    return false;
                }
            }
            return true;
        }
        if (first != '@' && first != '$' && first != '/') {
            return false;
        }
        if (n < 2 || !isTokenChar(s.charAt(1), true)) {
            return false;
        }
        for (int i = 2; i < n; i++) {
            if (!isTokenChar(s.charAt(i), false)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTokenChar(char c, boolean first) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || (!first && c >= '0' && c <= '9');
    }

    private static int toInt(LinkTier tier) {
        if (tier == LinkTier.TRUSTED) {
            return TIER_TRUSTED;
        }
        if (tier == LinkTier.NOT_WEB) {
            return TIER_NOT_WEB;
        }
        if (tier == LinkTier.DANGEROUS) {
            return TIER_DANGEROUS;
        }
        // UNKNOWN, and any tier a newer core adds that this build has not
        // heard of: not knowing what something is, is what "unknown" means.
        return TIER_UNKNOWN;
    }

    /**
     * The full decision for the dialogs; null when the guard is not active (or
     * there is no URL to decide about).
     *
     * <p>Unlike {@link #tierOf} this one fails closed. The core promises not to
     * throw, but if it ever does the answer is "unknown site", which asks the
     * user — not null, which {@link LinkGuardUi} reads as "guard off, open it".
     *
     * @param displayText the text the message showed for this link when it is
     *                    not the URL itself (a text link), otherwise null
     */
    public static LinkDecision decide(String url, String displayText) {
        LinkPolicy current = policy;
        if (current == null || url == null || !enabled()) {
            return null;
        }
        try {
            // "Hidden" is the core's word for a destination the message text
            // did not show. It is a note for the explanation, not a verdict.
            boolean hidden = displayText != null && displayText.length() > 0
                    && !displayText.trim().equals(url.trim());
            return current.decide(url, hidden, displayText);
        } catch (Throwable t) {
            android.util.Log.w(LOG_TAG, "link guard: decide failed: " + t.getClass().getSimpleName());
            return new LinkDecision(LinkTier.UNKNOWN, LinkReason.UNPARSEABLE, "", "", null, null, null,
                    Collections.<String>emptyList(), Collections.<String>emptyList(), current.getListsVersion());
        }
    }

    /**
     * Bumped whenever the lists or the toggle change. A span caches
     * {@code (tier, generation)} and asks again when this has moved on; read it
     * before {@link #tierOf}, not after (see there). Never 0.
     */
    public static int generation() {
        return generation.get();
    }

    /**
     * Called after the thing the tiers depend on has already changed. The memo
     * goes first and the counter second: a reader that sees the new generation
     * is then guaranteed to look things up in a memo that never held an
     * old-state answer.
     */
    private static void bumpGeneration() {
        memo = new Memo();
        generation.incrementAndGet();
    }

    /** From {@link HumogramConfig#setLinkGuard}, after the new value is in place. */
    static void onToggleChanged(Context context, boolean on) {
        try {
            bumpGeneration();
            redrawChats();
            if (on) {
                // It may have been off for days; do not wait for the next tick.
                startRefresh(MODE_AUTO);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // redraw
    // ------------------------------------------------------------------

    /**
     * The tiers may have changed: repaint the chats that are open. Nothing is
     * laid out again — a link's colour is resolved when it is drawn — so an
     * invalidate is enough, and {@code emojiLoaded} is the notification every
     * message cell and chat list already answers with exactly that.
     */
    private static void redrawChats() {
        MAIN.post(() -> {
            try {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.emojiLoaded);
            } catch (Throwable ignored) {
            }
        });
    }

    // ------------------------------------------------------------------
    // the cached list
    // ------------------------------------------------------------------

    /**
     * Worker only. Reads the stored envelope and puts it in force — after
     * checking its signature again, because what is on disk is only as
     * trustworthy as the last thing that wrote there. Idempotent; every worker
     * task calls it first, so nothing can run against a half-initialised store
     * whatever order tasks were queued in.
     */
    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        Context context = appContext;
        LinkPolicy current = policy;
        EngineFactory.ReferenceData data = referenceData;
        if (context == null || current == null || data == null) {
            return;
        }

        // Warm the toggle here, so the first draw pass does not pay for the
        // preferences read.
        HumogramConfig.isLinkGuard(context);

        publicKeys = readPublicKeys(context);
        userAgent = "Humogram/" + versionCode(context);
        store = LinkGuardStore.open(context);
        // From here on a refresh has everything it dereferences. Whatever
        // goes wrong below costs the cached list, not the ability to fetch
        // a new one.
        loaded = true;

        LinkGuardStore.Meta meta = store.readMeta();
        checkedAt = meta.checkedAt;
        lastResult = meta.lastResult >= RESULT_NEVER && meta.lastResult <= RESULT_TLS_ERROR
                ? meta.lastResult : RESULT_NEVER;
        // Held to the same ceiling as a downloaded version: a floor from the
        // far future — left by a list that should never have been accepted, or
        // by whatever else got at the file — would otherwise refuse every list
        // there will ever be. The cached envelope below is deliberately not
        // held to it: it passed when it was downloaded, and dropping it
        // because the clock has since been set back would only lose the
        // owner's list.
        versionFloor = Math.min(meta.version, latestPlausibleVersion());

        boolean applied = false;
        byte[] envelope = store.readEnvelope();
        if (envelope != null) {
            try {
                String payload = SignedLists.verify(new String(envelope, "UTF-8"), publicKeys);
                DomainLists lists = EngineFactory.parseDomainLists(data, payload);
                applied = current.update(lists);
                if (applied) {
                    versionFloor = Math.max(versionFloor, lists.getVersion());
                }
            } catch (Throwable t) {
                android.util.Log.w(LOG_TAG, "link guard: cached list rejected: " + t.getClass().getSimpleName());
            }
            if (!applied) {
                // Tampered with, truncated, or signed by a key this build no
                // longer carries. It will not verify next time either.
                store.dropEnvelope();
            }
        }

        if (applied) {
            etag = meta.etag;
            changedAt = meta.changedAt;
            // A process is started far more often than the list changes — by
            // every push, for one. If the last one checked a minute ago, this
            // one does not need to.
            long age = System.currentTimeMillis() - checkedAt;
            if (checkedAt > 0 && age >= 0 && age < MIN_INTERVAL_MS) {
                lastAttemptOk = true;
                lastAttemptAt = SystemClock.elapsedRealtime() - age;
            }
        } else {
            // Nothing to be conditional about: the next GET must be a full one.
            etag = null;
            changedAt = 0L;
        }

        if (applied) {
            bumpGeneration();
            redrawChats();
        }
    }

    private static List<String> readPublicKeys(Context context) {
        List<String> keys = new ArrayList<>(2);
        try {
            for (String key : context.getString(R.string.jac_linkguard_pubkeys).trim().split("\\s+")) {
                if (key.length() > 0) {
                    keys.add(key);
                }
            }
        } catch (Throwable ignored) {
            // No keys means no list can verify, and the bundled one stays in
            // force: the failure is recorded as a signature failure
            // (meta.json's lastResult) rather than hidden as a success.
        }
        return Collections.unmodifiableList(keys);
    }

    /** The build number: the one thing the request says about the app, so the owner can see how old the clients are. */
    private static long versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // refresh
    // ------------------------------------------------------------------

    /**
     * Ask the server whether the list changed; a conditional GET on the worker.
     * Safe from any thread and returns at once. Ignored within the minimum
     * interval of the last check, while the feature is switched off, and
     * while there is no connection.
     */
    public static void refresh() {
        startRefresh(MODE_AUTO);
    }

    private static void startRefresh(int mode) {
        try {
            if (policy == null) {
                return;
            }
            if (!enabled()) {
                // Switched off means silent: no background traffic for a
                // feature the user declined.
                return;
            }
            long last = lastAttemptAt;
            if (last != NEVER) {
                long floor = !lastAttemptOk ? RETRY_FLOOR_MS
                        : (mode == MODE_TICK ? TICK_FLOOR_MS : MIN_INTERVAL_MS);
                if (SystemClock.elapsedRealtime() - last < floor) {
                    return;
                }
            }
            if (!inFlight.compareAndSet(false, true)) {
                return;
            }
            try {
                WORKER.execute(LinkGuard::runRefresh);
            } catch (Throwable t) {
                inFlight.set(false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Worker only. */
    private static void runRefresh() {
        try {
            ensureLoaded();
            Context context = appContext;
            LinkPolicy current = policy;
            if (context == null || current == null || !loaded) {
                return;
            }
            if (!hasNetwork(context)) {
                // Not an attempt and not a failure: nothing is recorded, so the
                // next trigger is free to try.
                return;
            }

            int result = fetch(context, current);

            lastAttemptOk = result == RESULT_OK || result == RESULT_NOT_MODIFIED;
            lastAttemptAt = SystemClock.elapsedRealtime();
            lastResult = result;
            persistMeta();
            if (result == RESULT_OK) {
                bumpGeneration();
                redrawChats();
            }
        } catch (Throwable t) {
            android.util.Log.w(LOG_TAG, "link guard: refresh failed: " + t.getClass().getSimpleName());
        } finally {
            inFlight.set(false);
        }
    }

    /**
     * Whether there is a connection to try. Assumes yes when it cannot tell:
     * the cost of a wrong yes is one failed connect, the cost of a wrong no is
     * a list that never updates.
     */
    private static boolean hasNetwork(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return true;
            }
            NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Worker only. One conditional GET, and if there is a new document, its
     * verification. Returns a {@code RESULT_*}; never throws.
     */
    private static int fetch(Context context, LinkPolicy current) {
        byte[] body;
        String newEtag;
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(context.getString(R.string.jac_linkguard_lists_url).trim());
            URLConnection opened = "https".equalsIgnoreCase(url.getProtocol()) ? url.openConnection() : null;
            if (!(opened instanceof HttpsURLConnection)) {
                // A build with a mistyped list URL. Refusing is the point: the
                // signature would still protect the content, but a cleartext
                // request tells everyone on the path which app this is.
                android.util.Log.e(LOG_TAG, "link guard: list URL is not https");
                return RESULT_NETWORK_ERROR;
            }
            connection = (HttpsURLConnection) opened;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                // Before Android 7.1.1 the system store does not have the root
                // this host's certificate chains to, and the handshake fails
                // every time. For this connection only: the system store plus
                // that root (see LinkGuardTrust). Hostname verification is
                // untouched, and the list's own signature is still what
                // decides whether it is used.
                SSLSocketFactory ownTrust = LinkGuardTrust.socketFactory();
                if (ownTrust != null) {
                    connection.setSSLSocketFactory(ownTrust);
                }
            }
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            // One fixed host. A redirect is either a misconfiguration or
            // somebody else's page, and neither gets followed.
            connection.setInstanceFollowRedirects(false);
            // This is the whole request: the build number and, when we hold a
            // list, its ETag. No cookie, no token, no query, no device id.
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/json");
            if (etag != null) {
                connection.setRequestProperty("If-None-Match", etag);
            }

            int code = connection.getResponseCode();
            if (code == HttpsURLConnection.HTTP_NOT_MODIFIED && etag != null) {
                checkedAt = System.currentTimeMillis();
                return RESULT_NOT_MODIFIED;
            }
            if (code != HttpsURLConnection.HTTP_OK) {
                return RESULT_HTTP_ERROR;
            }
            newEtag = LinkGuardStore.cleanEtag(connection.getHeaderField("ETag"));
            body = readBody(connection);
        } catch (SSLException e) {
            // Something answered, and no secure channel came of it: a
            // certificate this phone does not trust, a captive portal, a
            // clock years out. Nothing changes here either, but it gets its
            // own word on the settings page — "no connection" would send the
            // user to check a Wi-Fi that is working, and the owner to check a
            // server that is up.
            return RESULT_TLS_ERROR;
        } catch (Throwable t) {
            // Offline, DNS, a timeout, a reset. Nothing changes: the list in
            // force stays in force.
            return RESULT_NETWORK_ERROR;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        if (body == null) {
            return RESULT_BAD_DOCUMENT;
        }
        return apply(current, body, newEtag);
    }

    /** The response body, or null if it is larger than any list we would accept. */
    private static byte[] readBody(HttpsURLConnection connection) throws IOException {
        int declared = connection.getContentLength();
        if (declared > LinkGuardStore.MAX_ENVELOPE_BYTES) {
            return null;
        }
        long deadline = SystemClock.elapsedRealtime() + BODY_BUDGET_MS;
        InputStream in = connection.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(declared > 0 ? declared : 32 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                // Counted after decompression, which is where it matters: the
                // platform inflates gzip transparently, and a few kilobytes on
                // the wire can be a great deal more than that in memory.
                if (out.size() > LinkGuardStore.MAX_ENVELOPE_BYTES) {
                    return null;
                }
                if (SystemClock.elapsedRealtime() > deadline) {
                    throw new IOException("list download too slow");
                }
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Worker only. Signature, document, version — in that order, and the list
     * in force is replaced only if all three hold.
     */
    private static int apply(LinkPolicy current, byte[] body, String newEtag) {
        String payload;
        try {
            payload = SignedLists.verify(new String(body, "UTF-8"), publicKeys);
        } catch (IllegalArgumentException e) {
            return isSignatureFailure(e) ? RESULT_BAD_SIGNATURE : RESULT_BAD_DOCUMENT;
        } catch (Throwable t) {
            return RESULT_BAD_DOCUMENT;
        }

        DomainLists lists;
        try {
            lists = EngineFactory.parseDomainLists(referenceData, payload);
        } catch (Throwable t) {
            // Correctly signed and still not a list we can read: a newer
            // format, or a mistake on the server. Keep what we have.
            return RESULT_BAD_DOCUMENT;
        }

        // A version from further ahead than any clock drifts is not a
        // publication time. Accepting it would raise the floor below past
        // everything an honest server will publish for years (see
        // MAX_VERSION_AHEAD_S), so the document is refused whole, before the
        // floor is looked at.
        if (lists.getVersion() > latestPlausibleVersion()) {
            return RESULT_BAD_DOCUMENT;
        }

        // A lower version is an old list being replayed, or a server restored
        // from an old backup. Either way it would un-block whatever was
        // blocked since, so it is refused. The floor covers the case the
        // policy cannot see for itself: the cached list was lost, and the
        // version in memory is back to zero.
        if (lists.getVersion() < versionFloor || !current.update(lists)) {
            return RESULT_STALE_VERSION;
        }

        long now = System.currentTimeMillis();
        // Stored exactly as downloaded, signature included, so that the next
        // start can verify it again. If the write fails the list is still in
        // force for this process; forgetting the ETag makes the next refresh
        // download and store it again instead of trusting a 304.
        etag = store.writeEnvelope(body) ? newEtag : null;
        versionFloor = lists.getVersion();
        changedAt = now;
        checkedAt = now;
        return RESULT_OK;
    }

    /** The highest version a list may carry right now: this phone's clock plus {@link #MAX_VERSION_AHEAD_S}. */
    private static long latestPlausibleVersion() {
        return System.currentTimeMillis() / 1000L + MAX_VERSION_AHEAD_S;
    }

    /**
     * "Not signed by a key we hold" as opposed to "not an envelope at all".
     * The core reports both as {@link IllegalArgumentException} and starts the
     * message with which ({@code SignedLists.verify}: {@code bad signature},
     * {@code no usable public key}, {@code malformed envelope},
     * {@code unsupported alg}). The two are worth telling apart on the
     * settings page: the first means somebody else's list, or a build whose
     * keys are wrong; the second usually means a broken server.
     */
    private static boolean isSignatureFailure(IllegalArgumentException e) {
        String message = e.getMessage();
        return message != null
                && (message.startsWith("bad signature") || message.startsWith("no usable public key"));
    }

    /** Worker only. */
    private static void persistMeta() {
        LinkGuardStore.Meta meta = new LinkGuardStore.Meta();
        meta.etag = etag;
        meta.checkedAt = checkedAt;
        meta.changedAt = changedAt;
        meta.lastResult = lastResult;
        meta.version = versionFloor;
        // The same failure every minute on a phone with no signal should not
        // be a disk write every minute.
        String fingerprint = etag + "|" + checkedAt + "|" + changedAt + "|" + lastResult + "|" + versionFloor;
        if (fingerprint.equals(writtenMeta)) {
            return;
        }
        if (store.writeMeta(meta)) {
            writtenMeta = fingerprint;
        }
    }

    // ------------------------------------------------------------------
    // foreground
    // ------------------------------------------------------------------

    private static final ForegroundDetector.Listener FOREGROUND = new ForegroundDetector.Listener() {
        @Override
        public void onBecameForeground() {
            enterForeground();
        }

        @Override
        public void onBecameBackground() {
            foreground = false;
            MAIN.removeCallbacks(TICK);
            // Nobody is looking at a chat, so nothing needs the remembered
            // URLs; do not keep them around for the hours the app may sit
            // here. The tiers did not change, so the generation stays.
            memo = new Memo();
        }
    };

    /**
     * Re-posts itself only from the foreground: while the app is in the
     * background nothing is scheduled at all, rather than scheduled and
     * skipped, so a backgrounded process is not woken every five minutes to
     * decide to do nothing.
     */
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!foreground) {
                return;
            }
            startRefresh(MODE_TICK);
            scheduleTick();
        }
    };

    /**
     * {@code ApplicationLoader} creates the {@code ForegroundDetector} a few
     * lines after it installs us, so there is nothing to register with yet.
     * The first attempt is posted to the main looper and therefore runs once
     * {@code onCreate} has returned, when the detector exists; the retries are
     * for a future upstream that creates it later still, and they give up
     * rather than poll for ever. Without the hook the list is still refreshed
     * at start-up and at every tap.
     */
    private static void registerForeground(final int attempt) {
        MAIN.postDelayed(() -> {
            try {
                ForegroundDetector detector = ForegroundDetector.getInstance();
                if (detector == null) {
                    if (attempt + 1 < REGISTER_ATTEMPTS) {
                        registerForeground(attempt + 1);
                    }
                    return;
                }
                detector.addListener(FOREGROUND);
                // The first activity may already have started: its launch can
                // be queued on the main looper ahead of this runnable, and a
                // listener added afterwards is never told.
                if (detector.isForeground()) {
                    enterForeground();
                }
            } catch (Throwable ignored) {
            }
        }, attempt == 0 ? 0L : REGISTER_RETRY_MS);
    }

    /** UI thread. */
    private static void enterForeground() {
        foreground = true;
        startRefresh(MODE_AUTO);
        scheduleTick();
    }

    /** UI thread. */
    private static void scheduleTick() {
        MAIN.removeCallbacks(TICK);
        // ±10 %, so that a list change is not followed by every phone in the
        // country asking for it in the same second, five minutes on the dot
        // after they all unlocked for the evening news.
        long jitter = (long) ((Math.random() * 0.2 - 0.1) * TICK_MS);
        MAIN.postDelayed(TICK, TICK_MS + jitter);
    }
}
