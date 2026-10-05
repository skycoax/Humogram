package uz.jac.secure.android;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The link guard's two files on disk: the signed site list exactly as the
 * server sent it, and a few facts about when it was fetched.
 *
 * <h3>Why the envelope is kept and not the parsed list</h3>
 *
 * {@code lists.json} is the downloaded envelope byte for byte — payload and
 * signature together — and {@link LinkGuard} verifies the signature again
 * every time it reads the file back. Storing the parsed list instead would
 * mean that whatever can write to this directory decides which sites the app
 * calls trusted; storing the envelope means the only lists that can ever come
 * out of this file are ones the owner's key signed. A file that no longer
 * verifies is deleted and the app falls back to its bundled list, under which
 * an unknown site is still an unknown site.
 *
 * <h3>What is in {@code meta.json}, and what is not</h3>
 *
 * The ETag that goes with the stored envelope, when the server last answered,
 * when the list last changed, the outcome of the last attempt, and the highest
 * list version this install has accepted. Nothing about the user: no link, no
 * host, no chat. The list is the same public file for everybody and these are
 * facts about fetching it.
 *
 * <p>The version is kept here as well as inside the envelope so that losing
 * the envelope does not reopen the rollback window: an old, validly signed
 * list replayed at a phone whose cache was wiped is still refused.
 *
 * <h3>Where and how</h3>
 *
 * {@code getNoBackupFilesDir()/jac-linkguard/}: app-private, and out of cloud
 * backup because a restored list would be a stale list on a new phone that is
 * about to download the current one anyway. Every write goes to a temporary
 * file that is synced and renamed over the target, so a crash leaves the
 * previous file rather than half of a new one — and half an envelope is not a
 * harmless thing to find at the next start, it is a list that fails its
 * signature and gets thrown away.
 *
 * <p>Not thread-safe. It belongs to {@link LinkGuard}'s single worker thread,
 * which is also what keeps disk I/O off the UI thread.
 */
final class LinkGuardStore {

    static final String DIR_NAME = "jac-linkguard";

    /**
     * Upper bound on an envelope, downloaded or read back. The service caps
     * the payload at 1 MiB; JSON string escaping and the signature wrapper
     * account for the rest. Anything larger is not our file.
     */
    static final int MAX_ENVELOPE_BYTES = 2 * 1024 * 1024;

    private static final String LISTS_FILE = "lists.json";
    private static final String META_FILE = "meta.json";
    private static final int MAX_META_BYTES = 16 * 1024;
    private static final int MAX_ETAG_CHARS = 200;

    /** What {@code meta.json} holds. A plain mutable record; see the class comment. */
    static final class Meta {
        /** ETag of the stored envelope, or null when there is none to be conditional about. */
        String etag;
        /** Wall-clock millis of the last answer we accepted (a verified 200, or a 304); 0 = never. */
        long checkedAt;
        /** Wall-clock millis when the stored envelope was downloaded; 0 = none. */
        long changedAt;
        /** One of {@code LinkGuard.RESULT_*}. */
        int lastResult;
        /** Highest list version ever accepted; a downloaded list below it is a rollback. */
        long version;
    }

    private final File dir;

    /** @param dir the directory to keep both files in; created on first write */
    LinkGuardStore(File dir) {
        this.dir = dir;
    }

    static LinkGuardStore open(Context context) {
        return new LinkGuardStore(new File(context.getNoBackupFilesDir(), DIR_NAME));
    }

    // ------------------------------------------------------------------
    // the signed envelope
    // ------------------------------------------------------------------

    /**
     * The stored envelope, or null when there is none, it cannot be read, or
     * it is larger than any envelope we would have accepted. Never throws.
     */
    byte[] readEnvelope() {
        return readBytes(new File(dir, LISTS_FILE), MAX_ENVELOPE_BYTES);
    }

    /** Replace the stored envelope. False if it could not be written; the old one then survives. */
    boolean writeEnvelope(byte[] envelope) {
        if (envelope == null || envelope.length == 0 || envelope.length > MAX_ENVELOPE_BYTES) {
            return false;
        }
        return writeAtomically(new File(dir, LISTS_FILE), envelope);
    }

    /** Forget the stored envelope: it failed its signature, or no longer parses. */
    void dropEnvelope() {
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, LISTS_FILE).delete();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // meta
    // ------------------------------------------------------------------

    /**
     * What was recorded, or a blank record. Never null, never throws.
     *
     * <p>Every field is read defensively: this file is not signed, so nothing
     * in it may be able to do more than make the app fetch the list again.
     */
    Meta readMeta() {
        Meta meta = new Meta();
        try {
            byte[] bytes = readBytes(new File(dir, META_FILE), MAX_META_BYTES);
            if (bytes == null) {
                return meta;
            }
            JSONObject o = new JSONObject(new String(bytes, "UTF-8"));
            meta.etag = cleanEtag(o.isNull("etag") ? null : o.optString("etag", null));
            meta.checkedAt = Math.max(0L, o.optLong("checked_at", 0L));
            meta.changedAt = Math.max(0L, o.optLong("changed_at", 0L));
            meta.lastResult = o.optInt("last_result", 0);
            meta.version = Math.max(0L, o.optLong("version", 0L));
        } catch (Throwable t) {
            // Corrupt: start blank. The cost is one unconditional download.
            return new Meta();
        }
        return meta;
    }

    boolean writeMeta(Meta meta) {
        try {
            JSONObject o = new JSONObject();
            if (meta.etag != null) {
                o.put("etag", meta.etag);
            }
            o.put("checked_at", meta.checkedAt);
            o.put("changed_at", meta.changedAt);
            o.put("last_result", meta.lastResult);
            o.put("version", meta.version);
            return writeAtomically(new File(dir, META_FILE), o.toString().getBytes("UTF-8"));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * An ETag that is safe to store and to send back, or null.
     *
     * <p>The value comes from a response header and goes out again in a
     * request header, so it is held to what an entity tag can legally be:
     * visible ASCII, no spaces, no control characters, and short. Anything
     * else is dropped, which only costs a full download next time.
     */
    static String cleanEtag(String raw) {
        if (raw == null) {
            return null;
        }
        String etag = raw.trim();
        if (etag.isEmpty() || etag.length() > MAX_ETAG_CHARS) {
            return null;
        }
        for (int i = 0; i < etag.length(); i++) {
            char c = etag.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return null;
            }
        }
        return etag;
    }

    // ------------------------------------------------------------------
    // files
    // ------------------------------------------------------------------

    /**
     * Replace {@code target} so that a reader sees either the old file or the
     * new one, never a torn one: write a sibling temporary file, sync it to
     * disk, then rename it over the target (atomic on the same filesystem).
     */
    private static boolean writeAtomically(File target, byte[] content) {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            return false;
        }
        File tmp = new File(parent, target.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(content);
            out.flush();
            try {
                out.getFD().sync();
            } catch (IOException ignored) {
                // Some filesystems refuse fsync; the rename is still atomic.
            }
            out.close();
            out = null;
            if (tmp.renameTo(target)) {
                return true;
            }
            // rename(2) replaces the target atomically on Android's ext4/f2fs,
            // so this is only reached on a filesystem that refuses to replace.
            // Deleting first loses atomicity there, but the worst a crash in
            // between can leave is no file, which reads as "download it again".
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (tmp.renameTo(target)) {
                return true;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** The whole file, or null if it is missing, unreadable, empty or longer than {@code max}. */
    private static byte[] readBytes(File file, int max) {
        InputStream in = null;
        try {
            if (!file.isFile()) {
                return null;
            }
            long length = file.length();
            if (length <= 0 || length > max) {
                return null;
            }
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) length);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                // The length was checked above, but the file is read without a
                // lock; hold the bound on what was actually read as well.
                if (out.size() > max) {
                    return null;
                }
            }
            return out.size() > 0 ? out.toByteArray() : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
