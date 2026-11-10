package org.telegram.tgnet.rest.e2ee;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;

/**
 * T86 — persistent registry of backend file ids that are KNOWN to be
 * end-to-end encrypted (XOSC1 secret media, XOSF1/XOSF2 password files,
 * album thumbs). Two facts per file id:
 * <ul>
 *   <li><b>known</b> — the id belongs to an encrypted blob (recorded at the
 *       moment its keys are registered from an envelope);</li>
 *   <li><b>decrypted</b> — a download of this id finished with a successful
 *       plaintext decrypt (recorded by the FileLoadOperation finish gate).</li>
 * </ul>
 *
 * <p>Why this exists: the finish decrypt gate historically treated "no media
 * keys for this id" as "not an e2ee file" and passed the assembled download
 * through. For a file whose keys were lost (store wipe, journal loss), that
 * renamed raw CIPHERTEXT into the cache as the real video/file — the
 * integrity gate could not catch it because the server attestation is over
 * the ciphertext. With the registry, the gate is FAIL-CLOSED: a known-e2ee
 * id without keys can never finish as a "successful" download.
 *
 * <p>The second use is self-healing: a cache file for a known-e2ee id that
 * was never successfully decrypted IS the poisoned ciphertext artifact the
 * pre-T86 gate produced. FileLoadOperation deletes it and re-downloads, so
 * devices already carrying broken videos/GIFs recover without user action.
 *
 * <p>Persistence: per-account append-only binary log, PLAINTEXT — the ids
 * carry no secret (backend file ids are already visible to the server, and
 * the registry must stay readable even when the keystore-protected key
 * journal is not). Corrupt-tolerant: parsing stops at the first malformed
 * record and keeps everything before it. Compaction rewrites the log when
 * the raw record count explodes (dedupe), bounded so the file can never
 * grow without limit.
 */
public final class XoE2eeFileRegistry {

    private static final byte[] MAGIC = {'X', 'O', 'E', 'R'};
    private static final byte VERSION = 1;
    private static final int RECORD_BYTES = 9; // 1 tag byte + 8 id bytes

    /** Tag 0: the id is a known encrypted blob. */
    private static final byte TAG_KNOWN = 0;
    /** Tag 1: a download of this id finished with a verified plaintext decrypt. */
    private static final byte TAG_DECRYPTED = 1;

    /** Compaction threshold: rewrite the log when raw records exceed this. */
    private static final int MAX_RAW_RECORDS = 150_000;

    private static final XoE2eeFileRegistry[] instances = new XoE2eeFileRegistry[org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT];

    public static XoE2eeFileRegistry getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        XoE2eeFileRegistry registry;
        synchronized (XoE2eeFileRegistry.class) {
            registry = instances[account];
            if (registry == null) {
                registry = new XoE2eeFileRegistry(account);
                instances[account] = registry;
            }
        }
        return registry;
    }

    private final int account;
    private final Object lock = new Object();

    private final HashSet<Long> known = new HashSet<>();
    private final HashSet<Long> decrypted = new HashSet<>();

    private boolean loaded;
    private int rawRecords;
    private FileOutputStream appendStream;

    private XoE2eeFileRegistry(int account) {
        this.account = account;
    }

    // ------------------------------------------------------------------ public api

    /** Records that the file id belongs to an encrypted blob. */
    public void note(long backendFileId) {
        if (backendFileId <= 0) {
            return;
        }
        synchronized (lock) {
            loadLocked();
            if (!known.add(backendFileId)) {
                return; // already known — nothing to append
            }
            appendLocked(TAG_KNOWN, backendFileId);
        }
    }

    /** Records that a download of this id finished with a verified plaintext decrypt. */
    public void markDecrypted(long backendFileId) {
        if (backendFileId <= 0) {
            return;
        }
        synchronized (lock) {
            loadLocked();
            boolean added = known.add(backendFileId) | decrypted.add(backendFileId);
            if (added) {
                appendLocked(TAG_DECRYPTED, backendFileId);
            }
        }
    }

    /** TRUE when this backend file id is a known encrypted blob. */
    public boolean known(long backendFileId) {
        if (backendFileId <= 0) {
            return false;
        }
        synchronized (lock) {
            loadLocked();
            return known.contains(backendFileId);
        }
    }

    /** TRUE when a download of this id already finished with a verified plaintext decrypt. */
    public boolean isDecrypted(long backendFileId) {
        if (backendFileId <= 0) {
            return false;
        }
        synchronized (lock) {
            loadLocked();
            return decrypted.contains(backendFileId);
        }
    }

    /** Removes all state (main store wipe / account logout). */
    public void wipe() {
        synchronized (lock) {
            known.clear();
            decrypted.clear();
            rawRecords = 0;
            closeAppendStreamLocked();
            File file = registryFile();
            if (file.exists() && !file.delete()) {
                FileLog.e("XoE2eeFileRegistry: unable to delete " + file.getName());
            }
            loaded = true; // stay empty until something is noted again
        }
    }

    /** Test seam: fresh singleton state for the next getInstance (JVM suite). */
    public static void resetForTests() {
        synchronized (XoE2eeFileRegistry.class) {
            for (int a = 0; a < instances.length; a++) {
                instances[a] = null;
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    private void loadLocked() {
        if (loaded) {
            return;
        }
        loaded = true;
        File file = registryFile();
        if (!file.exists()) {
            return;
        }
        FileInputStream in = null;
        try {
            byte[] buf = new byte[(int) file.length()];
            in = new FileInputStream(file);
            int off = 0;
            while (off < buf.length) {
                int read = in.read(buf, off, buf.length - off);
                if (read < 0) {
                    break;
                }
                off += read;
            }
            int header = MAGIC.length + 1;
            if (buf.length < header || buf[0] != MAGIC[0] || buf[1] != MAGIC[1]
                    || buf[2] != MAGIC[2] || buf[3] != MAGIC[3] || buf[4] != VERSION) {
                FileLog.e("XoE2eeFileRegistry: unrecognized file, starting fresh");
                return;
            }
            int pos = header;
            while (pos + RECORD_BYTES <= buf.length) {
                byte tag = buf[pos];
                long id = readLongLE(buf, pos + 1);
                if (id > 0 && (tag == TAG_KNOWN || tag == TAG_DECRYPTED)) {
                    known.add(id);
                    if (tag == TAG_DECRYPTED) {
                        decrypted.add(id);
                    }
                    rawRecords++;
                } else {
                    // malformed tail (torn write) — keep everything before it
                    FileLog.e("XoE2eeFileRegistry: malformed record at " + pos + ", truncating tail");
                    break;
                }
                pos += RECORD_BYTES;
            }
        } catch (Throwable t) {
            FileLog.e("XoE2eeFileRegistry: load failed", t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
        compactIfNeededLocked();
    }

    private void appendLocked(byte tag, long backendFileId) {
        try {
            if (appendStream == null) {
                appendStream = new FileOutputStream(registryFile(), true);
            }
            byte[] record = new byte[RECORD_BYTES];
            record[0] = tag;
            writeLongLE(record, 1, backendFileId);
            appendStream.write(record);
            appendStream.flush();
            rawRecords++;
            compactIfNeededLocked();
        } catch (Throwable t) {
            FileLog.e("XoE2eeFileRegistry: append failed", t);
            closeAppendStreamLocked();
            // the in-memory set still holds the fact; the next append recreates
            // the stream and the next load tolerates a torn tail
        }
    }

    /**
     * Rewrites the log with one record per tracked id when the raw record
     * count explodes (append-heavy phases). Nothing is dropped — every
     * known/decrypted fact survives; the rewrite only collapses duplicates.
     */
    private void compactIfNeededLocked() {
        if (rawRecords <= MAX_RAW_RECORDS) {
            return;
        }
        closeAppendStreamLocked();
        File file = registryFile();
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(MAGIC);
            out.write(VERSION);
            for (Long id : known) {
                byte[] record = new byte[RECORD_BYTES];
                record[0] = TAG_KNOWN;
                writeLongLE(record, 1, id);
                out.write(record);
            }
            for (Long id : decrypted) {
                byte[] record = new byte[RECORD_BYTES];
                record[0] = TAG_DECRYPTED;
                writeLongLE(record, 1, id);
                out.write(record);
            }
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                file.delete();
                if (!tmp.renameTo(file)) {
                    throw new IllegalStateException("registry compaction rename failed");
                }
            }
            rawRecords = known.size() + decrypted.size();
        } catch (Throwable t) {
            FileLog.e("XoE2eeFileRegistry: compaction failed (will retry)", t);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void closeAppendStreamLocked() {
        if (appendStream != null) {
            try {
                appendStream.close();
            } catch (Exception ignore) {
            }
            appendStream = null;
        }
    }

    private File registryFile() {
        Context context = ApplicationLoader.applicationContext;
        return new File(context.getFilesDir(), "xo_e2ee_files_" + account + ".bin");
    }

    private static long readLongLE(byte[] buf, int off) {
        return (buf[off] & 0xFFL)
                | ((buf[off + 1] & 0xFFL) << 8)
                | ((buf[off + 2] & 0xFFL) << 16)
                | ((buf[off + 3] & 0xFFL) << 24)
                | ((buf[off + 4] & 0xFFL) << 32)
                | ((buf[off + 5] & 0xFFL) << 40)
                | ((buf[off + 6] & 0xFFL) << 48)
                | ((buf[off + 7] & 0xFFL) << 56);
    }

    private static void writeLongLE(byte[] buf, int off, long v) {
        buf[off] = (byte) v;
        buf[off + 1] = (byte) (v >> 8);
        buf[off + 2] = (byte) (v >> 16);
        buf[off + 3] = (byte) (v >> 24);
        buf[off + 4] = (byte) (v >> 32);
        buf[off + 5] = (byte) (v >> 40);
        buf[off + 6] = (byte) (v >> 48);
        buf[off + 7] = (byte) (v >> 56);
    }
}
