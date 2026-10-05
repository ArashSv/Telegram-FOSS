package org.telegram.tgnet.rest.e2ee;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.rest.RestFileBridge;
import org.telegram.tgnet.rest.RestGateway;
import org.telegram.tgnet.rest.XoFileTransport;

/**
 * T75 — client-side RE-ENCRYPTION of already-server-resident media into an
 * E2EE 1:1 chat. The "no plaintext reference" policy (an encrypted chat
 * must never reference a plaintext file row) previously REFUSED such sends:
 * GIF-tab taps, re-sent photos/files and plaintext forwards all died with
 * E2EE_PLAINTEXT_MEDIA_REFUSED. The policy is correct; what was missing is
 * the mechanism — this class IS the mechanism.
 *
 * <p>Pipeline (identical trust properties to a fresh upload): the plaintext
 * bytes are read through the authorized download route (owner, or shared in
 * a mutual chat — the same audience that could already render them), split
 * into fixed chunks, every chunk is AES-256-GCM encrypted under a FRESH
 * single-use 32B key with the SAME wire format the live upload funnel uses
 * (init → attested chunks with the sub-32 KB WAF pad → e2ee finalize), and
 * the result is a NEW opaque server file. The old plaintext row stays where
 * it was — the encrypted chat only ever references the new e2ee row, and
 * the only place the key travels is inside a Signal-Protocol envelope.
 *
 * <p>All-or-nothing per chunk: a failed chunk aborts the whole operation
 * (the caller fails the send loudly; no half-encrypted file is ever
 * referenced). A completed-but-abandoned run leaves an unreferenced orphan
 * blob the backend can GC — it is unreadable ciphertext without the key.
 */
public final class XoE2EEReencrypt {

    /** Fixed chunk size for re-encrypted bodies (96 KB — WAF-friendly, below the big-file 128 KB). */
    public static final int CHUNK = 96 * 1024;

    public static final class Result {
        public long backendFileId;
        public byte[] fileKey;
        public long plaintextLen;
        public int chunkSize;
        public int parts;
        // T75: source metadata (captured from the server's plaintext file row
        // via files/get.php BEFORE re-encryption) so the receiver can rebuild
        // the exact media shape. For TL_inputMediaDocument/Photo REFERENCES
        // the dispatcher has no attributes — the server row is the only
        // metadata source, and it is authoritative for plaintext files.
        public String mime;
        public String name;
        public int width, height, duration;
        public boolean animated;
    }

    private XoE2EEReencrypt() {
    }

    /**
     * Re-encrypts {@code sourceBackendFileId} into a fresh e2ee file.
     *
     * @param sourceSize plaintext byte total ({@link RestFileBridge#attestedSize};
     *                   a fallback cold-fetch of the metadata is attempted when 0)
     * @throws Exception on any download/encrypt/upload/finalize failure — the
     *                   caller must fail the send, never fall back to plaintext
     */
    public static Result reencrypt(int account, long sourceBackendFileId, long sourceSize) throws Exception {
        if (sourceBackendFileId <= 0) {
            throw new IllegalArgumentException("source file id required");
        }
        RestFileBridge bridge = RestFileBridge.getInstance(account);
        XoFileTransport transport = XoFileTransport.getInstance(account);

        if (sourceSize <= 0) {
            // metadata cold-fetch (same path a download would take) — the
            // attestation index is usually warm from the message parse
            try {
                JSONObject meta = RestGateway.getInstance(account).fileMetadata(sourceBackendFileId);
                JSONObject file = meta == null ? null : meta.optJSONObject("file");
                if (file != null) {
                    RestFileBridge.noteFileMetaFromJson(file);
                    sourceSize = file.optLong("size", 0);
                }
            } catch (Exception e) {
                FileLog.w("XoE2EEReencrypt: metadata cold-fetch failed: " + e.getMessage());
            }
        }
        if (sourceSize <= 0) {
            throw new Exception("source size unknown for file " + sourceBackendFileId);
        }
        // source row metadata for the envelope (mime/dims/name/gif flag) —
        // authoritative for plaintext rows; a failure degrades to a generic
        // octet-stream envelope (still fully encrypted, just less typed)
        JSONObject sourceMeta = null;
        try {
            JSONObject meta = RestGateway.getInstance(account).fileMetadata(sourceBackendFileId);
            sourceMeta = meta == null ? null : meta.optJSONObject("file");
            if (sourceMeta != null) {
                RestFileBridge.noteFileMetaFromJson(sourceMeta);
            }
        } catch (Exception e) {
            FileLog.w("XoE2EEReencrypt: source metadata unavailable (generic envelope): " + e.getMessage());
        }

        byte[] fileKey = XoE2EEMedia.newFileKey();
        long treeUploadId = Utilities.random.nextLong();
        int parts = (int) Math.max(1, (sourceSize + CHUNK - 1) / CHUNK);
        long backendId = bridge.ensureBackendFile(treeUploadId, parts);
        XoE2eeLog.event(account, "reencrypt.start", 0,
                "src=" + sourceBackendFileId + " dst=" + backendId + " parts=" + parts);
        long cipherTotal = 0;
        try {
            for (int i = 0; i < parts; i++) {
                long off = (long) i * CHUNK;
                long end = Math.min(sourceSize, off + CHUNK) - 1;
                byte[] plain = transport.downloadRange(sourceBackendFileId, off, end);
                if (plain == null || plain.length != end - off + 1) {
                    throw new Exception("short source read at chunk " + i
                            + " (" + (plain == null ? 0 : plain.length) + " of " + (end - off + 1) + "B)");
                }
                byte[] cipher = XoE2EEMedia.encryptChunk(fileKey, i, plain);
                cipherTotal += cipher.length;
                uploadAttestedPart(account, backendId, i, cipher);
                bridge.noteUploadBytes(treeUploadId, cipher.length);
            }
            JSONObject finalizeEnvelope = bridge.finalizeUploadE2ee(treeUploadId, parts, cipherTotal);
            JSONObject fileJson = finalizeEnvelope == null ? null : finalizeEnvelope.optJSONObject("file");
            if (fileJson == null || fileJson.optLong("file_id", 0) <= 0) {
                throw new Exception("e2ee finalize failed for the re-encrypted copy");
            }
            RestFileBridge.noteFileMetaFromJson(fileJson);
            Result result = new Result();
            result.backendFileId = fileJson.optLong("file_id", 0);
            result.fileKey = fileKey;
            result.plaintextLen = sourceSize;
            result.chunkSize = CHUNK;
            result.parts = parts;
            if (sourceMeta != null) {
                result.mime = sourceMeta.optString("mime_type", null);
                result.name = sourceMeta.isNull("name") ? null : sourceMeta.optString("name", null);
                result.width = sourceMeta.optInt("width", 0);
                result.height = sourceMeta.optInt("height", 0);
                result.duration = sourceMeta.optInt("duration", 0);
                String kind = sourceMeta.optString("kind", null);
                String srcMime = result.mime == null ? "" : result.mime;
                result.animated = "gif".equals(kind) || "image/gif".equals(srcMime);
            }
            XoE2eeLog.event(account, "reencrypt.ok", 0,
                    "src=" + sourceBackendFileId + " dst=" + result.backendFileId);
            return result;
        } catch (Exception e) {
            XoE2eeLog.event(account, "reencrypt.fail", 0,
                    "src=" + sourceBackendFileId + " " + e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * ONE attested part upload — byte-for-byte the wire contract of the live
     * funnel ({@code uploadPart}): the sub-32 KB WAF pad, the &len= real
     * length, and the size/sha echo verification with one idempotent re-send.
     */
    private static void uploadAttestedPart(int account, long backendId, int part, byte[] data) throws Exception {
        byte[] body = data;
        int realLen = 0;
        if (data.length < 32 * 1024) {
            body = new byte[32 * 1024];
            System.arraycopy(data, 0, body, 0, data.length);
            realLen = data.length;
        }
        String sentSha = sha256Hex(data);
        JSONObject storedEnvelope = RestGateway.getInstance(account).fileChunkAttested(backendId, part, body, realLen);
        long stored = storedEnvelope.optLong("size", -1);
        String storedSha = storedEnvelope.isNull("sha256") ? null : storedEnvelope.optString("sha256", null);
        boolean bad = (stored >= 0 && stored != data.length)
                || (storedSha != null && !storedSha.equalsIgnoreCase(sentSha));
        if (bad) {
            storedEnvelope = RestGateway.getInstance(account).fileChunkAttested(backendId, part, body, realLen);
            stored = storedEnvelope.optLong("size", -1);
            storedSha = storedEnvelope.isNull("sha256") ? null : storedEnvelope.optString("sha256", null);
            if ((stored >= 0 && stored != data.length)
                    || (storedSha != null && !storedSha.equalsIgnoreCase(sentSha))) {
                throw new Exception("part " + part + " attestation failed (stored " + stored + " of " + data.length + "B)");
            }
        }
    }

    private static String sha256Hex(byte[] data) {
        return Utilities.bytesToHex(Utilities.computeSHA256(data, 0, data.length));
    }
}
