<?php

/**
 * Service info / health check. Requires no database connection
 * (Db::pdo() is lazy), so it works even before config is filled in.
 */

declare(strict_types=1);

use App\Core\Config;
use App\Core\Response;

// Walk up until app/ is found (keeps working no matter where public/
// is deployed: docroot, subfolder, ...).
$dir = __DIR__;
$boot = null;
for ($i = 0; $i < 10; $i++) {
    if (is_file($dir . '/app/bootstrap.php')) {
        $boot = $dir . '/app/bootstrap.php';
        break;
    }
    $parent = dirname($dir);
    if ($parent === $dir) {
        break;
    }
    $dir = $parent;
}
if ($boot === null) {
    http_response_code(500);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode([
        'ok'    => false,
        'error' => [
            'code'    => 'BOOTSTRAP_FAILED',
            'message' => 'app/bootstrap.php not found. Keep app/ as a sibling of the deployed public/ directory.',
        ],
    ]);
    exit;
}

require $boot;

Response::json([
    'service'     => (string) Config::get('app.name', 'mymessenger-api'),
    'version'     => (string) Config::get('app.version', '1.0.0'),
    'server_time' => time(),
    'endpoints'   => [
        'auth'     => [
            'POST /api/v1/auth/check-phone.php',
            'POST /api/v1/auth/register.php',
            'POST /api/v1/auth/login.php',
            'POST /api/v1/auth/refresh.php',
            'GET  /api/v1/auth/me.php',
            'POST /api/v1/auth/verify-password.php',
            'POST /api/v1/auth/change-password.php',
        ],
        'users'    => ['GET /api/v1/users/get.php?ids=1,2,3'],
        'chats'    => [
            'GET  /api/v1/chats/list.php',
            'POST /api/v1/chats/create.php',
            'POST /api/v1/chats/add-member.php',
            'GET  /api/v1/chats/members.php?chat_id=1',
        ],
        'messages' => [
            'POST /api/v1/messages/send.php',
            'GET  /api/v1/messages/history.php?chat_id=1',
            'POST /api/v1/messages/edit.php',
            'POST /api/v1/messages/delete.php',
            'POST /api/v1/messages/read.php',
        ],
        'files'    => [
            'POST /api/v1/files/init.php',
            'POST /api/v1/files/chunk.php?file_id=1&index=0',
            'POST /api/v1/files/finalize.php',
            'GET  /api/v1/files/get.php?file_id=1',
            'GET  /api/v1/files/download.php?file_id=1',
        ],
        'sync'     => ['GET /api/v1/sync/index.php?cursor=0'],
    ],
]);
