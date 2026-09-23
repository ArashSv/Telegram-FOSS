package org.telegram.tgnet.rest;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import org.json.JSONObject;

/**
 * T8a: the tree-upload-id → backend-file-id bridge — the heart of the media
 * phase. The Telegram tree invents its own random {@code file_id}s for uploads
 * (FileUploadOperation currentFileId, referenced later by
 * {@code TL_inputMediaUploaded*.file.id}) and never shows them to the network
 * contract we own; the backend assigns its own sequence ids. This class owns
 * the ONLY mapping between the two namespaces.
 *
 * <p>Design decisions (PHASE3_PLAN.md §5, §7-T8a):
 * <ul>
 *   <li><b>Lazy init</b> — the dispatcher sees the first
 *       {@code TL_upload_save*FilePart} of a tree id and calls
 *       {@link #ensureBackendFile}, which POSTs {@code files/init.php} once
 *       ({@code size=0}: the tree may not know the size, the backend computes
 *       it from the assembled blob at finalize; part counts may extend on the
 *       fly server-side — backend v1.1b).</li>
 *   <li><b>Persisted mapping</b> — the tree PERSISTS its upload resume state
 *       across process death ({@code uploadinfo} SharedPreferences:
 *       {@code _id} + {@code _uploaded}), so after a kill+relaunch mid-upload
 *       it keeps streaming parts for the SAME tree id. An in-memory-only map
 *       would re-init a second backend file and finalize a blob missing the
 *       earlier parts. The mapping therefore lives in its own per-account
 *       prefs file ({@code xofilebridge}), written on init, never removed
 *       (entries are two longs; abandoned backend files are purged by the
 *       server-side cleanup-uploads tool when never finalized).</li>
 *   <li><b>Thread safety</b> — parts of one file can arrive in parallel on the
 *       dispatcher IO pool; {@link #ensureBackendFile} double-checks inside a
 *       lock so a file is initialized exactly once.</li>
 * </ul>
 *
 * <p>Media context (mime/name/w/h/duration) is NOT carried here: the tree only
 * reveals it in {@code TL_messages_sendMedia} AFTER the upload — the
 * send-media route passes it straight to finalize (single metadata injection
 * point, backend v1.1b).
 */
public final class RestFileBridge {

    private static final String PREFS_NAME = "xofilebridge";
    private static final String KEY_PREFIX = "tree2backend_";

    private static final RestFileBridge[] instances = new RestFileBridge[UserConfig.MAX_ACCOUNT_COUNT];

    /** Per-account singleton, keyed like the rest of the package. */
    public static RestFileBridge getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            account = 0;
        }
        RestFileBridge bridge;
        synchronized (RestFileBridge.class) {
            bridge = instances[account];
            if (bridge == null) {
                bridge = new RestFileBridge(account);
                instances[account] = bridge;
            }
        }
        return bridge;
    }

    private final int account;
    private final SharedPreferences prefs;
    /** Warm cache in front of prefs; guarded by itself. */
    private final android.util.LongSparseArray<Long> cache = new android.util.LongSparseArray<>();
    /** parentFileId -> thumbFileId (0 = none/failure); guarded by itself. */
    private final android.util.LongSparseArray<Long> thumbCache = new android.util.LongSparseArray<>();

    private RestFileBridge(int account) {
        this.account = account;
        this.prefs = ApplicationLoader.applicationContext.getSharedPreferences(
                PREFS_NAME + "_" + account, android.content.Context.MODE_PRIVATE);
    }

    /** Known backend id for a tree upload id, 0 when this id was never seen. */
    public long backendFileIdFor(long treeUploadId) {
        if (treeUploadId == 0) {
            return 0;
        }
        synchronized (cache) {
            Long cached = cache.get(treeUploadId);
            if (cached != null) {
                return cached;
            }
        }
        long persisted = prefs.getLong(KEY_PREFIX + treeUploadId, 0);
        if (persisted != 0) {
            synchronized (cache) {
                cache.put(treeUploadId, persisted);
            }
        }
        return persisted;
    }

    /**
     * Returns the backend file id for {@code treeUploadId}, creating the
     * backend file on first sight (files/init.php, size=0 + a placeholder
     * part count — the backend extends on out-of-range indices, v1.1b).
     *
     * @param chunksTotalEstimate best-known part count (tree
     *                            {@code file_total_parts}; 1 when unknown)
     * @throws XoApiException / {@link XoTransportException} on backend failures
     *                        (the upload part fails — the tree's typed error
     *                        path renders it, no crash)
     */
    public long ensureBackendFile(long treeUploadId, int chunksTotalEstimate) {
        long known = backendFileIdFor(treeUploadId);
        if (known != 0) {
            return known;
        }
        synchronized (this) {
            known = backendFileIdFor(treeUploadId);
            if (known != 0) {
                return known; // lost the race: another part already initialized
            }
            RestGateway gateway = RestGateway.getInstance(account);
            long backendId = gateway.fileInit(chunksTotalEstimate);
            if (backendId == 0) {
                throw new XoApiException(200, XoApiException.MALFORMED_RESPONSE, "file init answer without file_id");
            }
            synchronized (cache) {
                cache.put(treeUploadId, backendId);
            }
            prefs.edit().putLong(KEY_PREFIX + treeUploadId, backendId).apply();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("RestFileBridge: tree upload " + treeUploadId + " -> backend file " + backendId);
            }
            return backendId;
        }
    }

    /**
     * Finalizes the upload for a tree id (idempotent server-side) and returns
     * the backend File JSON — called from the send-media route where the tree
     * finally reveals mime/name/dimensions/duration. Returns null when the
     * tree id is unknown (upload never went through this client session —
     * caller answers a typed error).
     */
    public JSONObject finalizeUpload(long treeUploadId, int chunksTotal, String mime, String name,
                                     Integer width, Integer height, Integer duration) {
        long backendId = backendFileIdFor(treeUploadId);
        if (backendId == 0) {
            return null;
        }
        return RestGateway.getInstance(account).fileFinalize(backendId, chunksTotal, mime, name, width, height, duration);
    }

    /**
     * Resolves a DOWNLOADER's file location to the backend file id to stream
     * (T8b synthetic-id contract, PHASE3_PLAN.md §5, factory-consistent):
     * <ul>
     *   <li>{@code TL_inputFileLocation} (photos): the receive mapper plants
     *       {@code volume_id = -parentFileId} on every size — identical to the
     *       tree's PhotoSize factory reconstruction after a storage reload —
     *       so {@code |volume_id|} is the parent id and the local_id letter
     *       selects thumb vs full;</li>
     *   <li>{@code TL_inputDocumentFileLocation} (documents): {@code id} is the
     *       backend file id; a local_id in [1000, 2000) is the document-thumb
     *       letter (the tree's factory convention).</li>
     * </ul>
     * Thumb requests resolve through {@link #thumbFileIdFor} (one cached
     * metadata call per file); when a file has no thumb or the lookup fails,
     * the full file streams — the UI still renders, only with more bytes.
     * Returns 0 for unsupported locations (caller rejects with a typed error).
     */
    public long resolveBackendFileId(TLRPC.InputFileLocation location) {
        long parentId;
        boolean thumbRequest = false;
        if (location instanceof TLRPC.TL_inputPhotoFileLocation) {
            // our photo sizes ALWAYS arrive here: ImageLocation keeps a Photo
            // parent (photoId != 0, thumbSize = the size letter), and
            // FileLoadOperation then builds TL_inputPhotoFileLocation
            parentId = ((TLRPC.TL_inputPhotoFileLocation) location).id;
            String thumbSize = ((TLRPC.TL_inputPhotoFileLocation) location).thumb_size;
            thumbRequest = PHOTO_SIZE_THUMB.equals(thumbSize);
        } else if (location instanceof TLRPC.TL_inputFileLocation) {
            // legacy path for ImageLocations without a parent object
            long volumeId = ((TLRPC.TL_inputFileLocation) location).volume_id;
            parentId = Math.abs(volumeId);
            int localId = ((TLRPC.TL_inputFileLocation) location).local_id;
            thumbRequest = localId == 's' || localId == 'm';
        } else if (location instanceof TLRPC.TL_inputDocumentFileLocation) {
            parentId = ((TLRPC.TL_inputDocumentFileLocation) location).id;
            int localId = ((TLRPC.TL_inputDocumentFileLocation) location).local_id;
            // document-thumb factory convention: local_id = 1000 + letter
            // (the tree copies both volume_id and local_id for thumb requests)
            thumbRequest = localId >= 1000 && localId < 2000;
        } else {
            return 0;
        }
        if (parentId <= 0) {
            return 0;
        }
        return thumbRequest ? thumbFileIdFor(parentId) : parentId;
    }

    /**
     * Thumb file id for a parent file, cached in memory (0 = none/failure).
     * One tiny JSON metadata call per parent file per process — thumbs are
     * then streamed straight from their own file id.
     */
    public long thumbFileIdFor(long parentFileId) {
        synchronized (thumbCache) {
            if (thumbCache.indexOfKey(parentFileId) >= 0) {
                return thumbCache.get(parentFileId, 0);
            }
        }
        long thumbId = 0;
        try {
            JSONObject file = RestGateway.getInstance(account)
                    .fileMetadata(parentFileId)
                    .optJSONObject("file");
            if (file != null) {
                thumbId = file.optLong("thumb_file_id", 0);
            }
        } catch (Exception e) {
            FileLog.w("RestFileBridge: thumb metadata lookup failed for file " + parentFileId + ", streaming full file");
        }
        synchronized (thumbCache) {
            thumbCache.put(parentFileId, thumbId);
        }
        return thumbId;
    }
}
