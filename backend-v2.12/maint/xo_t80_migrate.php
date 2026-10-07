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

// standalone DB bootstrap (no app/config dependency on the live layout)
$configCandidates = [
    __DIR__ . '/config/config.php',
];
$config = null;
foreach ($configCandidates as $path) {
    if (is_file($path)) {
        $config = require $path;
        break;
    }
}
if (!is_array($config)) {
    @unlink(__FILE__);
    http_response_code(500);
    echo json_encode(['ok' => false, 'error' => 'config not found']);
    exit;
}

$db = $config['db'] ?? [];
$applied = [];
$skipped = [];
$errors = [];

try {
    $dsn = sprintf('mysql:host=%s;dbname=%s;charset=utf8mb4', $db['host'], $db['database']);
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

    // 2. update_queue enum extension (idempotent: MODIFY is safe to re-run)
    $stmt = $pdo->prepare(
        "SELECT COLUMN_TYPE FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'update_queue' AND COLUMN_NAME = 'type'"
    );
    $stmt->execute();
    $colType = (string) $stmt->fetchColumn();
    if (strpos($colType, 'chat_mode') === false) {
        $pdo->exec("ALTER TABLE update_queue MODIFY COLUMN type ENUM(
            'message_new','message_edit','message_delete','read','chat_new',
            'user_updated','chat_member','gifs','user_status','chat_mode') NOT NULL");
        $applied[] = 'update_queue.type enum + chat_mode';
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

    $counts = [
        'chats_total'  => (int) $pdo->query('SELECT COUNT(*) FROM chats')->fetchColumn(),
        'chats_cloud'  => (int) $pdo->query("SELECT COUNT(*) FROM chats WHERE mode = 'cloud'")->fetchColumn(),
        'chats_secret' => (int) $pdo->query("SELECT COUNT(*) FROM chats WHERE mode = 'secret'")->fetchColumn(),
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
