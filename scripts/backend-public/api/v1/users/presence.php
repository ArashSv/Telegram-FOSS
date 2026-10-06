<?php

declare(strict_types=1);

require_once __DIR__ . '/../_bootstrap.php';

use App\Controllers\UsersController;
use App\Core\Request;
use App\Core\Response;

Response::json(UsersController::presence(Request::capture()));
