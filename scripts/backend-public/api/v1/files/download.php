<?php

declare(strict_types=1);

require_once __DIR__ . '/../_bootstrap.php';

use App\Controllers\FilesController;
use App\Core\Request;

// Streams binary output (200, or 206 with a Range header); exits internally.
FilesController::download(Request::capture());
