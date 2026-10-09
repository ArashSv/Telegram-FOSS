<?php

/**
 * T80 one-shot migration (v2.12.0) — token-gated, SELF-DELETING.
 *
 * Applies the two-mode chat schema to the LIVE MySQL database:
 *   1. chats.mode            VARCHAR(16) NOT NULL DEFAULT 'cloud'
 *      (every existing chat backfills to 'cloud' — the T80 default that
 *      stops broken E2EE sessions from being load-bearing)
 *   2. update_queue.type     enum extension + 'chat_mode'
 *   3. messages album/forward columns (guarded per column — added by the
 *      T56 era on some environments, missing on others; idempotent here)
 *
 * Usage (exactly one GET):
 *   GET /xo_t80_migrate.php?t=<DIAG_TOKEN>
 * Wrong/missing token: reports nothing useful and DELETES ITSELF.
 * Right token: runs, prints a JSON report, DELETES ITSELF.
 */

declare(strict_types=1);

const EXPECTED_TOKEN = '__DIAG_TOKEN__';

header('Content-Type: application/json; charset=utf-8');

$token = isset($_GET['t']) ? (string) $_GET['t'] : '';
if (!hash_equals(EXPECTED_TOKEN, $token)) {
    // T55 discipline: a wrong token deletes the script immediately. The diag
    // hashes (first 8 hex of sha256) let the operator match configured vs
    // received tokens WITHOUT leaking either.
    $diag = [
        'gone'      => true,
        'recv_len'  => strlen($token),
        'recv_sha8' => substr(hash('sha256', $token), 0, 8),
        'exp_sha8'  => substr(hash('sha256', EXPECTED_TOKEN), 0, 8),
    ];
    @unlink(__FILE__);
    http_response_code(404);
    echo json_encode($diag);
    exit;
}

// standalone DB bootstrap (config lives OUTSIDE the docroot on the live
// host — /home/USER/config/) — resolve it exactly like the app does:
// walk up from this file's directory until app/bootstrap.php is found;
// the config sits beside it (config/config.php).
$dir = __DIR__;
$config = null;
for ($i = 0; $i < 10; $i++) {
    foreach ([$dir . '/config/config.php'] as $candidate) {
        if (is_file($candidate)) {
            $config = require $candidate;
            break 2;
        }
    }
    $parent = dirname($dir);
    if ($parent === $dir) {
        break;
    }
    $dir = $parent;
}
if (!is_array($config)) {
    @unlink(__FILE__);
    http_response_code(500);
    echo json_encode(['ok' => false, 'error' => 'config not found (walked up from ' . __DIR__ . ')']);
    exit;
}

$db = $config['db'] ?? [];
$applied = [];
$skipped = [];
$errors = [];

try {
    $dsn = sprintf('mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4',
        $db['host'], (int) ($db['port'] ?? 3306), $db['name']);
    $pdo = new PDO($dsn, $db['user'], $db['password'], [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_TIMEOUT => 15,
    ]);

    $colExists = static function (PDO $pdo, string $table, string $column): bool {
        $stmt = $pdo->prepare(
            'SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?'
        );
        $stmt->execute([$table, $column]);
        return (int) $stmt->fetchColumn() > 0;
    };

    // 1. chats.mode
    if (!$colExists($pdo, 'chats', 'mode')) {
        $pdo->exec("ALTER TABLE chats ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'cloud' AFTER type");
        $applied[] = 'chats.mode ADD';
    } else {
        $skipped[] = 'chats.mode already present';
    }

    // 2. update_queue enum extension — UNION with the LIVE values. The
    //    live enum carries MORE types than this build knows about
    //    (dialog_pin / privacy / blocked ...), and MODIFY-ing with a
    //    narrower list truncates existing rows (error 1265). Never narrow.
    $stmt = $pdo->prepare(
        "SELECT COLUMN_TYPE FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'update_queue' AND COLUMN_NAME = 'type'"
    );
    $stmt->execute();
    $colType = (string) $stmt->fetchColumn(); // e.g. enum('message_new',...)
    if (strpos($colType, 'chat_mode') === false) {
        preg_match_all("/'([^']+)'/", $colType, $m);
        $values = $m[1] ?: [];
        if (!in_array('chat_mode', $values, true)) {
            $values[] = 'chat_mode';
        }
        $enumSql = "ENUM('" . implode("','", $values) . "') NOT NULL";
        $pdo->exec("ALTER TABLE update_queue MODIFY COLUMN type " . $enumSql);
        $applied[] = 'update_queue.type enum + chat_mode (union of ' . count($values) . ' values)';
    } else {
        $skipped[] = 'update_queue.type already includes chat_mode';
    }

    // 3. messages album/forward columns (guarded, idempotent)
    $msgCols = [
        'group_id'         => 'ALTER TABLE messages ADD COLUMN group_id BIGINT UNSIGNED DEFAULT NULL',
        'fwd_from_user_id' => 'ALTER TABLE messages ADD COLUMN fwd_from_user_id BIGINT UNSIGNED DEFAULT NULL',
        'fwd_from_chat_id' => 'ALTER TABLE messages ADD COLUMN fwd_from_chat_id BIGINT UNSIGNED DEFAULT NULL',
        'fwd_from_msg_id'  => 'ALTER TABLE messages ADD COLUMN fwd_from_msg_id BIGINT UNSIGNED DEFAULT NULL',
        'fwd_from_name'    => 'ALTER TABLE messages ADD COLUMN fwd_from_name VARCHAR(100) DEFAULT NULL',
        'fwd_date'         => 'ALTER TABLE messages ADD COLUMN fwd_date BIGINT UNSIGNED DEFAULT NULL',
    ];
    foreach ($msgCols as $column => $ddl) {
        if (!$colExists($pdo, 'messages', $column)) {
            $pdo->exec($ddl);
            $applied[] = 'messages.' . $column . ' ADD';
        } else {
            $skipped[] = 'messages.' . $column . ' already present';
        }
    }

    // ---- v2.12.1 (T78): the separate secret-chat schema -------------------
    // 4. users.secret_pk / users.secret_pk_updated — the X25519 public-key
    //    registry (private keys never leave the device).
    if (!$colExists($pdo, 'users', 'secret_pk')) {
        $pdo->exec("ALTER TABLE users ADD COLUMN secret_pk VARCHAR(64) DEFAULT NULL AFTER token_ver");
        $applied[] = 'users.secret_pk ADD';
    } else {
        $skipped[] = 'users.secret_pk already present';
    }
    if (!$colExists($pdo, 'users', 'secret_pk_updated')) {
        $pdo->exec('ALTER TABLE users ADD COLUMN secret_pk_updated BIGINT UNSIGNED DEFAULT NULL AFTER secret_pk');
        $applied[] = 'users.secret_pk_updated ADD';
    } else {
        $skipped[] = 'users.secret_pk_updated already present';
    }

    // 5. chats.type ENUM widened with 'secret' — UNION with the LIVE values
    //    (the T80 fix8 lesson: never narrow an enum). Appending at the END
    //    is metadata-only.
    $stmt2 = $pdo->prepare(
        "SELECT COLUMN_TYPE FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chats' AND COLUMN_NAME = 'type'"
    );
    $stmt2->execute();
    $chatTypeCol = (string) $stmt2->fetchColumn();
    if (strpos($chatTypeCol, 'secret') === false) {
        preg_match_all("/'([^']+)'/", $chatTypeCol, $m2);
        $typeValues = $m2[1] ?: [];
        if (!in_array('secret', $typeValues, true)) {
            $typeValues[] = 'secret';
        }
        $typeEnumSql = "ENUM('" . implode("','", $typeValues) . "') NOT NULL";
        $pdo->exec('ALTER TABLE chats MODIFY COLUMN type ' . $typeEnumSql);
        $applied[] = 'chats.type enum + secret (union of ' . count($typeValues) . ' values)';
    } else {
        $skipped[] = 'chats.type already includes secret';
    }

    // 6. mode-flip retirement (T78: the user replaced the T80 in-place mode
    //    flip with SEPARATE secret chats). Legacy flipped private chats
    //    revert to cloud — their XOE1 history is dead by design (the client
    //    skips those rows); NEW secret conversations happen in dedicated
    //    chats.type='secret' rows, which are born secret and never flipped.
    $reset = $pdo->exec("UPDATE chats SET mode = 'cloud' WHERE mode = 'secret' AND type <> 'secret'");
    if ($reset > 0) {
        $applied[] = 'mode-flip retirement: ' . $reset . ' chat(s) back to cloud';
    } else {
        $skipped[] = 'no legacy secret-mode chats to retire';
    }

    $counts = [
        'chats_total'  => (int) $pdo->query('SELECT COUNT(*) FROM chats')->fetchColumn(),
        'chats_cloud'  => (int) $pdo->query("SELECT COUNT(*) FROM chats WHERE mode = 'cloud'")->fetchColumn(),
        'chats_secret' => (int) $pdo->query("SELECT COUNT(*) FROM chats WHERE mode = 'secret'")->fetchColumn(),
        'chats_type_secret' => (int) $pdo->query("SELECT COUNT(*) FROM chats WHERE type = 'secret'")->fetchColumn(),
        'users_with_secret_pk' => (int) $pdo->query('SELECT COUNT(*) FROM users WHERE secret_pk IS NOT NULL')->fetchColumn(),
    ];

    $report = ['ok' => true, 'applied' => $applied, 'skipped' => $skipped, 'counts' => $counts];
} catch (Throwable $e) {
    $errors[] = $e->getMessage();
    $report = ['ok' => false, 'applied' => $applied, 'skipped' => $skipped, 'errors' => $errors];
}

$deleted = @unlink(__FILE__);
$report['self_deleted'] = $deleted;

http_response_code($report['ok'] ? 200 : 500);
echo json_encode($report, JSON_PRETTY_PRINT);
