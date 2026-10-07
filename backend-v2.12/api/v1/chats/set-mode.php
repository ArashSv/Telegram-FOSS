<?php

declare(strict_types=1);

require_once __DIR__ . '/../_bootstrap.php';

use App\Controllers\ChatsController;
use App\Core\Request;
use App\Core\Response;

// v2.12.0 (T80): flip a private chat's encryption mode (cloud <-> secret).
Response::json(ChatsController::setMode(Request::capture()));
