<?php
// T77 field-evidence probe v2 — token-gated, self-deleting, read-only.
// Uses the SAME deployment shape as the proven T76 probe: lives in the
// backend docroot and requires the backend bootstrap (no manual PDO/eval).
$__expected = '__TOKEN__';
$__prog = __DIR__ . '/_xo_t77_progress.txt';
$__step = function ($s) use ($__prog) { @file_put_contents($__prog, gmdate('H:i:s') . ' ' . $s . "\n", FILE_APPEND); };
@unlink($__prog);
if (!isset($_GET['t']) || !is_string($_GET['t']) || !hash_equals($__expected, hash('sha256', (string) $_GET['t']))) {
    $__step('GATE FAIL len_exp=' . strlen($__expected) . ' len_got=' . strlen((string) ($_GET['t'] ?? '')));
    $__step('echo done');
@unlink(__FILE__);
    http_response_code(406);
    header('Content-Type: application/json');
    exit(json_encode(['gate' => 'fail', 'exp_len' => strlen($__expected), 'got_len' => strlen((string) ($_GET['t'] ?? ''))]));
}
$__step('gate ok');

define('XO_MAINT', 1);
$__step('before bootstrap');
require __DIR__ . '/api/v1/_bootstrap.php';
$__step('bootstrap ok');

use App\Core\Db;

header('Content-Type: application/json; charset=utf-8');

$report = [];
$report['php'] = PHP_VERSION;

// 0) THE docroot error_log tail — this explains any 500 including ours.
$lg = __DIR__ . '/error_log';
if (@is_file($lg)) {
    $lines = @file($lg, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [];
    $report['docroot_error_log_tail'] = array_slice($lines, -30);
    $report['error_log_size'] = @filesize($lg);
} else {
    $report['docroot_error_log_tail'] = 'missing';
}

$__step('before queries');
function q($sql, $args = []) {
    try { return Db::fetchAll($sql, $args); } catch (Throwable $e) { return [['__err' => $e->getMessage()]]; }
}

// 1) account map
$report['users'] = q("SELECT id, phone, LEFT(COALESCE(username,''),24) u, LEFT(COALESCE(name,''),24) n
                       FROM users WHERE id >= 10000 ORDER BY id");

// 2) recent chats: ordering + envelope wire-type analysis
$chats = q("SELECT chat_id, COUNT(*) cnt, MAX(created_at) last_t
             FROM messages WHERE created_at > UNIX_TIMESTAMP()-259200
             GROUP BY chat_id ORDER BY last_t DESC LIMIT 12");
foreach ($chats as $k => $c) {
    if (isset($c['__err'])) { break; }
    $rows = q("SELECT id, sender_id, created_at, content FROM messages WHERE chat_id = ? ORDER BY id DESC LIMIT 60", [$c['chat_id']]);
    if (isset($rows[0]['__err'])) { $chats[$k]['__err'] = $rows[0]['__err']; continue; }
    $rows = array_reverse($rows);
    $inv = 0; $pre = 0; $wsp = 0; $plain = 0; $other = 0; $prevT = 0; $seq = '';
    foreach ($rows as $r) {
        if ($prevT && (int)$r['created_at'] < $prevT) { $inv++; }
        $prevT = (int)$r['created_at'];
        $co = (string)$r['content'];
        if (strpos($co, 'XOE1:') === 0) {
            $b = base64_decode(substr($co, 5), true);
            $wt = ($b !== false && $b !== '') ? ord($b[0]) : -1;
            if ($wt === 3) { $pre++; $seq .= 'P'; }
            elseif ($wt === 2) { $wsp++; $seq .= 'w'; }
            else { $other++; $seq .= '?'; }
        } else { $plain++; $seq .= '.'; }
    }
    $chats[$k]['inv_60'] = $inv;
    $chats[$k]['wire_60'] = "pre=$pre wsp=$wsp plain=$plain other=$other";
    $chats[$k]['seq'] = $seq;
    unset($chats[$k]['last_t']);
}
$report['recent_chats'] = $chats;

// 3) relayed Hermes reports (owner Saved Messages)
$report['owner_relay'] = q("SELECT id, chat_id, FROM_UNIXTIME(created_at) t, LEFT(content, 1100) c
                             FROM messages WHERE content LIKE 'Hermes %' ORDER BY id DESC LIMIT 5");

// 4) update_queue backlog
$report['queue'] = q("SELECT type, COUNT(*) n, MIN(id) mn, MAX(id) mx FROM update_queue GROUP BY type");

// 5) newest client log files (tail 40)
$logDir = APP_ROOT . '/storage/client_logs';
$files = [];
if (is_dir($logDir)) {
    $cand = glob($logDir . '/android_log-*.log');
    usort($cand, function ($a, $b) { return filemtime($b) <=> filemtime($a); });
    foreach (array_slice($cand, 0, 8) as $f) {
        $lines = @file($f, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [];
        $files[] = ['f' => basename($f), 'mt' => gmdate('m-d H:i', (int)filemtime($f)), 'n' => count($lines),
                    'tail' => array_slice($lines, -40)];
    }
}
$report['client_logs'] = $files;
$report['now'] = gmdate('Y-m-d H:i:s');
$__step('queries done, before echo');

echo json_encode($report, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
$__step('echo done');
@unlink(__FILE__);
