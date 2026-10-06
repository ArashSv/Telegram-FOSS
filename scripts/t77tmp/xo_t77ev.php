<?php
// T77 field-evidence probe — token gated, self-deleting, read-only DB access.
$__T = '__TOKEN__';
if (!isset($_GET['t']) || !hash_equals($__T, (string)$_GET['t'])) { http_response_code(403); echo 'forbidden'; exit; }
header('Content-Type: application/json; charset=utf-8');
error_reporting(E_ALL);
ini_set('display_errors', '0');
$R = [];
try {
$dir = __DIR__; $root = null;
for ($i = 0; $i < 10; $i++) { if (is_file($dir.'/app/bootstrap.php')) { $root = $dir; break; } $p = dirname($dir); if ($p === $dir) break; $dir = $p; }
try { $cfg = eval('?>' . (string)@file_get_contents($root.'/config/config.php')); }
catch (Throwable $e) { echo json_encode(['cfg' => 'FATAL '.$e->getMessage()]); @unlink(__FILE__); exit; }
$pdo = new PDO(sprintf('mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4', $cfg['db']['host'], (int)($cfg['db']['port'] ?? 3306), $cfg['db']['name']),
    $cfg['db']['user'], $cfg['db']['password'], [PDO::ATTR_TIMEOUT => 10, PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);

function q($pdo, $sql, $args = []) {
    $st = $pdo->prepare($sql); $st->execute($args); return $st->fetchAll(PDO::FETCH_ASSOC);
}

// 1) account map (special accounts)
$R['users'] = q($pdo, "SELECT id, phone, LEFT(COALESCE(username,''),24) u, LEFT(COALESCE(name,''),24) n FROM users WHERE id >= 10000 ORDER BY id");

// 2) chats with activity in the last 72h
$chats = q($pdo, "SELECT chat_id, COUNT(*) cnt, MIN(id) mn, MAX(id) mx, MAX(created_at) last_t
                   FROM messages WHERE created_at > UNIX_TIMESTAMP()-259200 GROUP BY chat_id ORDER BY last_t DESC LIMIT 12");
foreach ($chats as &$c) {
    $rows = q($pdo, "SELECT id, sender_id, created_at, content FROM messages WHERE chat_id = ? ORDER BY id DESC LIMIT 60", [$c['chat_id']]);
    $rows = array_reverse($rows);
    $inv = 0; $wire = ['pre' => 0, 'wsp' => 0, 'plain' => 0, 'other' => 0];
    $prevT = 0; $types = [];
    foreach ($rows as $r) {
        if ($prevT && (int)$r['created_at'] < $prevT) $inv++;
        $prevT = (int)$r['created_at'];
        $co = (string)$r['content'];
        if (strpos($co, 'XOE1:') === 0) {
            $b = base64_decode(substr($co, 5), true);
            $wt = ($b !== false && $b !== '') ? ord($b[0]) : -1;
            if ($wt === 3) { $wire['pre']++; $types[] = 'pre'; }
            elseif ($wt === 2) { $wire['wsp']++; $types[] = 'wsp'; }
            else { $wire['other']++; $types[] = 'oth'.$wt; }
        } else { $wire['plain']++; $types[] = 'plain'; }
    }
    $c['inv_60'] = $inv;             // created_at inversions within id order (last 60)
    $c['wire_60'] = $wire;
    $seq = '';
    foreach ($types as $t) { $seq .= ($t === 'pre') ? 'P' : (($t === 'wsp') ? 'w' : (($t === 'plain') ? '.' : '?')); }
    $c['seq'] = $seq;
    unset($c['mn']); unset($c['mx']);
}
$R['recent_chats'] = $chats;

// 3) relayed e2ee reports into the owner's chat (kind=e2ee bodies)
$owner = q($pdo, "SELECT id FROM users WHERE phone = '+40400000' LIMIT 1");
if ($owner) {
    $R['owner_relay'] = q($pdo, "SELECT id, sender_id, chat_id, FROM_UNIXTIME(created_at) t, LEFT(content, 1200) c
                                  FROM messages WHERE content LIKE 'Hermes %' ORDER BY id DESC LIMIT 6");
}

// 4) update_queue backlog
$R['queue'] = q($pdo, "SELECT type, COUNT(*) n, MIN(id) mn, MAX(id) mx FROM update_queue GROUP BY type");

// 5) newest client log files (tail)
$logDir = $root . '/storage/client_logs';
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
$R['client_logs'] = $files;
$R['now'] = gmdate('Y-m-d H:i:s');
echo json_encode($R, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
} catch (Throwable $e) {
    echo json_encode(['probe_error' => $e->getMessage(), 'line' => $e->getLine(), 'php' => PHP_VERSION]);
}
@unlink(__FILE__);
