<?php

/**
 * Shared bootstrap for every endpoint file under public/api/v1/*.
 *
 * Each endpoint is a DIRECT .php file (shared-hosting friendly: no
 * mod_rewrite needed), e.g.:
 *     https://host/api/v1/messages/send.php
 * Endpoint files only do:
 *     require_once __DIR__ . '/../_bootstrap.php';
 *     Response::json(SomeController::action(Request::capture()));
 */

declare(strict_types=1);

// Walk up from this directory until app/bootstrap.php is found, so the
// repository layout works both as a plain checkout and uploaded onto a
// shared host (public/* -> public_html/, app/ -> /home/USER/app/).
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
