<?php

declare(strict_types=1);

namespace App\Core;

/**
 * Cloud-chat at-rest message encryption (v2.12.0 / T80).
 *
 * Cloud chats store message content AES-256-GCM encrypted under a SERVER-HELD
 * key (Telegram cloud-chat semantics: the server is a trusted participant;
 * the protection target is database/backup exfiltration, not the server
 * itself). Secret chats keep their client-side opaque XOE1: envelopes and
 * never pass through here.
 *
 * Storage format in messages.content:
 *   XOC1: . base64( nonce[12] || ciphertext+tag[16] )
 *   - key      : HKDF(master, info='xoc1:v1:chat:<chatId>')  per-chat
 *   - AAD      : 'xoc1:msg:<msgId>'                          row-bound
 *   The AAD binding makes ciphertext transplanting between rows or chats
 *   detectable (GCM tag breaks), and the versioned XOC1: prefix leaves room
 *   for future re-key sweeps (XOC2: ...) without flag days.
 *
 * Master key resolution (deterministic per environment, zero-config):
 *   1. Config 'crypto.master_key' (base64 of 32 raw bytes) when present —
 *      the explicit operator-supplied key.
 *   2. Otherwise HKDF-SHA256 derived from the existing jwt.secret with the
 *      dedicated info label 'xoc1:master'. Stable across restarts because
 *      jwt.secret is stable; never equal to the JWT signing material itself.
 *
 * Passthrough contract (single read boundary in MessageMapper::json):
 *   content without the XOC1: prefix is returned unchanged — legacy
 *   plaintext rows and secret-chat XOE1: envelopes stay byte-exact.
 */
final class CloudCrypto
{
    private const PREFIX = 'XOC1:';
    private const CIPHER = 'aes-256-gcm';
    private const NONCE_BYTES = 12;
    private const KEY_BYTES = 32;

    private static ?string $masterKey = null;

    private function __construct()
    {
    }

    /** Encrypt a cloud-chat message for storage. Null/'' pass through untouched. */
    public static function encrypt(int $chatId, int $msgId, ?string $plain): ?string
    {
        if ($plain === null || $plain === '') {
            return $plain;
        }
        // Defensive: never double-wrap (a stale client pushing XOE1 into a
        // cloud chat is refused at the controller; an already-XOC1 payload
        // would be a logic bug upstream — keep it verbatim rather than
        // wrapping an envelope in an envelope).
        if (str_starts_with($plain, self::PREFIX) || str_starts_with($plain, 'XOE1:')) {
            return $plain;
        }
        $nonce = random_bytes(self::NONCE_BYTES);
        $tag = '';
        $cipher = openssl_encrypt(
            $plain,
            self::CIPHER,
            self::chatKey($chatId),
            OPENSSL_RAW_DATA,
            $nonce,
            $tag,
            self::aad($msgId),
        );
        if ($cipher === false) {
            throw new \RuntimeException('cloud crypto encrypt failed');
        }
        return self::PREFIX . base64_encode($nonce . $cipher . $tag);
    }

    /**
     * Chat-aware decrypt (the entry point MessageMapper uses — the chat id
     * participates in the key derivation, so it must be supplied by the
     * caller who already has the row).
     */
    public static function decryptForChat(int $chatId, int $msgId, ?string $stored): ?string
    {
        if ($stored === null || $stored === '' || !str_starts_with($stored, self::PREFIX)) {
            return $stored;
        }
        $raw = base64_decode(substr($stored, strlen(self::PREFIX)), true);
        if ($raw === false || strlen($raw) < self::NONCE_BYTES + 16) {
            self::report($msgId, 'malformed envelope');
            return null;
        }
        $nonce = substr($raw, 0, self::NONCE_BYTES);
        $tag = substr($raw, -16);
        $cipher = substr($raw, self::NONCE_BYTES, -16);
        $plain = openssl_decrypt(
            $cipher,
            self::CIPHER,
            self::chatKey($chatId),
            OPENSSL_RAW_DATA,
            $nonce,
            $tag,
            self::aad($msgId),
        );
        if ($plain === false) {
            self::report($msgId, 'gcm tag failure');
            return null;
        }
        return $plain;
    }

    /** True when the stored content is a cloud envelope (diagnostics/tests). */
    public static function isEnvelope(?string $stored): bool
    {
        return $stored !== null && str_starts_with($stored, self::PREFIX);
    }

    private static function aad(int $msgId): string
    {
        return 'xoc1:msg:' . $msgId;
    }

    private static function chatKey(int $chatId): string
    {
        return hash_hkdf('sha256', self::masterKey(), self::KEY_BYTES, 'xoc1:v1:chat:' . $chatId);
    }

    private static function masterKey(): string
    {
        if (self::$masterKey !== null) {
            return self::$masterKey;
        }
        $configured = Config::get('crypto.master_key');
        if (is_string($configured) && $configured !== '') {
            $raw = base64_decode($configured, true);
            if ($raw !== false && strlen($raw) === self::KEY_BYTES) {
                return self::$masterKey = $raw;
            }
            throw new \RuntimeException('config crypto.master_key must be base64 of exactly 32 bytes');
        }
        // Zero-config fallback: derive from the existing per-environment
        // jwt secret under a dedicated info label (never used as JWT key
        // material itself).
        $secret = (string) Config::get('jwt.secret', '');
        if ($secret === '') {
            throw new \RuntimeException('no crypto.master_key and no jwt.secret to derive one from');
        }
        return self::$masterKey = hash_hkdf('sha256', $secret, self::KEY_BYTES, 'xoc1:master');
    }

    private static function report(int $msgId, string $why): void
    {
        error_log(sprintf('[security] cloud decrypt failed msg=%d: %s', $msgId, $why));
    }
}
