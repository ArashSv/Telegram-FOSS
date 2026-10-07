<?php

declare(strict_types=1);

require_once __DIR__ . '/../_bootstrap.php';

use App\Controllers\ChatsController;
use App\Core\Request;
use App\Core\Response;

// v2.7.0 (T56): per-user dialog pin state (restored in T80).
Response::json(ChatsController::pin(Request::capture()));
