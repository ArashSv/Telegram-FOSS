# T63 — Project test suites (backend + client)

Two cooperating suites cover the whole Hermes stack:

## 1. Backend — `tests/` in the backend tree (dependency-free PHP)

Lives next to the API code (`tests/`, see `tests/README.md` in the backend
tree). 15 suites / 384 checks drive the REAL controllers through the same
`Request`/`ApiError` machinery the deployed endpoint files use — auth +
multi-account 4-rule entitlement, the +404 namespace, users/contacts/chats/
messages/files/gifs, sync event scoping, privacy/blocked matrices and a
full auth-bypass/IDOR sweep. Runner: `php tests/run_all.php` (exit code is
CI-grade; fresh sqlite DB per suite process).

## 2. Client — JVM contract tests (`TMessagesProj/src/test`)

JUnit 4 tests that parse **frozen REAL backend outputs** with the
production mapper classes. Equivalent: the backend bed's
`tests/make_fixtures.php` captures the exact JSON bodies of
v2.10.0 endpoints into `tests/fixtures/*.json`; those files are copied to
`TMessagesProj/src/test/resources/fixtures/` verbatim. A backend wire
change therefore breaks the client build **before** it can reach a device.

Test targets (all package-local, no Android runtime needed):

| class | pins |
|-------|------|
| `TlJsonMapperUserTest` | public/self user shapes, privacy strips (status/photo/bio), contact hydration, flag coherence, null-sentinel traps |
| `TlJsonMapperMessageTest` | text/reply/media messages, history ordering, album group_id, edit flag, mention offsets, group rights |
| `PrivacyWireMappingTest` | privacy rule wire -> TL rules, exception hydration, sync event shapes (privacy payloadless / blocked {user_id, blocked, user, date}) |
| `EnvelopeContractTest` | ok/error/malformed envelopes + every deployed error code (USER_IS_BLOCKED, PRIVACY_KEY_UNSUPPORTED, MULTI_ACCOUNT_FORBIDDEN, ...) + lifecycle classification |
| `RestRouterRouteTest` | the default-deny route table (every ROUTE_* wired, unknown = deny) |
| `XoSpecialAccountsTest` | the 10 demo numbers, exact-match mapping, owner wire phone = 40411130 |
| `ClientPrimitivesTest` | sender hydration ids, server-corrected clock anchors, transport/api exception split |

`parseEnvelope` was made static package-private (behavior-identical) so the
envelope contract is testable without instantiating the gateway.

## CI gate

`.github/workflows/build.yml` runs `unit-tests` FIRST
(`./gradlew :TMessagesProj:testDebugUnitTest`); the APK `build` job has
`needs: [unit-tests]` — a failing contract test blocks every release.

## Local loop (backend)

```
/home/z/my-project/scripts/bin/php tests/run_all.php        # 384 checks
/home/z/my-project/scripts/bin/php tests/make_fixtures.php  # refresh fixtures
cp tests/fixtures/*.json <repo>/TMessagesProj/src/test/resources/fixtures/
```
