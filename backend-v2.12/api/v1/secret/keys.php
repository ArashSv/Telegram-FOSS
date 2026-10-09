<?php

declare(strict_types=1);

require_once __DIR__ . '/../_bootstrap.php';

use App\Controllers\SecretController;
use App\Core\ApiError;
use App\Core\Request;
use App\Core\Response;

/**
 * v2.12.0 (T78) — secret-chat public-key registry.
 *   PUT/POST {pk}        -> register/replace the caller's public key
 *   GET [?user_id=N]     -> fetch own / a peer's public key (pure SELECT)
 */
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
if ($method === 'PUT' || $method === 'POST') {
    Response::json(SecretController::putKey(Request::capture()));
}
if ($method !== 'GET') {
    throw new ApiError('VALIDATION_ERROR', 'Method not allowed', 405);
}
Response::json(SecretController::getKey(Request::capture()));
