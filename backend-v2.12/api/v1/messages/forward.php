<?php

declare(strict_types=1);

require_once __DIR__ . '/../_bootstrap.php';

use App\Controllers\MessagesController;
use App\Core\Request;
use App\Core\Response;

// v2.7.0 (T56): Telegram-style forwarding (restored in T80 — the wrapper
// file was missing from the deployed copy; the controller logic never left).
Response::json(MessagesController::forward(Request::capture()));
