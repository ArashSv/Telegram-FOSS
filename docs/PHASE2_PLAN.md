# Phase 2 Plan — Android client (REST-ified Telegram-FOSS fork)

Status: APPROVED-FOR-EXECUTION draft v1 (pre-fork). Companion to `ANDROID_PLAN.md`
(kept as the architecture record) — this document contains the **verified facts**,
the **critical corrections** discovered by confronting that plan with the real
v10.14.3 source tree, and the executable task graph.

Backend contract frozen at: `docs/API.md` (v1, live at https://xorbit.ir/tele).

---

## 1. Verified baseline (recon, 2026-09-21)

| Item | Fact | Consequence |
|---|---|---|
| Fork base | `Telegram-FOSS-Team/Telegram-FOSS` @ `master`, gradle.properties says `APP_VERSION_NAME=10.14.3`, latest tag only `v9.7.6` | master is ahead of releases. **Pin the exact commit** as branch `base/upstream-<sha>` before any surgery |
| Toolchain | Gradle 7.2, AGP 7.0.3, buildTools 33.0.0, compileSdk/target 33, NDK 21.4.7075529, cmake 3.10.2 | **JDK 11 mandatory** (Gradle 7.2 cannot run on JDK 17+/21). Do NOT modernize toolchain in Phase 2 — one variable at a time |
| Config-time file | `TMessagesProj/build.gradle` reads `./API_KEYS` (`APP_ID`, `APP_HASH`) or fails configuration | Repo must ship `API_KEYS` (dummy values OK — fields become dead once MTProto is stripped) |
| Build targets | flavors `bundleAfat`, `bundleAfat_SDK23`, `afat`; debug only survives variantFilter for `*afat*` | Validation target: `:TMessagesProj_App:assembleAfatDebug` |
| Native | 4 submodules (ffmpeg, boringssl, libvpx, libwebp) + NDK r21 | Full native build ≈ 6–9 GB disk / 8 GB+ RAM → **CI-only**. Java-only build (skip externalNativeBuild) ≈ 3.6 GB → sandbox-feasible |
| Repo footprint | shallow clone w/o submodules = 307 MB; 2,530 java files in `src/main/java` | Known build scale |
| Sandbox | 9.2 GB free, 3.9 GB RAM, 2 cores, JDK 21 only | Track A (Java-only) here; Track B (full APK) on GitHub Actions |

## 2. Critical corrections to ANDROID_PLAN.md

The plan's architecture stands (facade over `ConnectionsManager`, OkHttp REST,
cursor polling). Confronting it with the real tree produced 6 corrections:

1. **Routing is default-deny, not exhaustive.** Real tree has **751
   `ConnectionsManager.getInstance` call sites / 1,012 `sendRequest` calls**.
   `EndpointMapper` routes only the ~25 TL methods our API v1 implements
   (auth, users/get, chats, messages, files, sync). Everything else returns
   `TL_error(400, "NOT_IMPLEMENTED")` **synchronously** — UI degrades
   (empty lists, dead buttons) instead of hanging spinners. Feature UIs are
   hidden progressively; hanging is forbidden.
2. **Do NOT delete `serializeToStream`/`readParams` bodies** (plan's strip
   list). Once `tgnet` is gone they are dead code; a scripted mass-edit of
   `TLRPC.java` costs merge conflicts with upstream forever, for zero runtime
   gain. TLRPC stays byte-identical in Phase 2.
3. **Secret chats: neutralize, don't amputate.** 12 files reference
   `SecretChatHelper`. Phase 2 keeps the class but cuts entry points (menu,
   `MessagesController` init paths) — physical removal moves to Phase 4.
4. **`getDifference` (22 call sites outside tgnet) must be stubbed, not
   routed.** `UpdatePoller` replaces it; `MessagesController.getDifference`
   gets an immediate no-op callback so lifecycle callers never spin.
5. **Auth interception point is `LoginActivity` (36 `sendRequest` calls) +
   the `AuthController`/`SendCodeHelper` path.** Token store: `UserConfig`
   extension + EncryptedSharedPreferences, per plan.
6. **Facade containment is confirmed**: all 6 `sendRequest` overloads funnel
   into one canonical method; 34 native methods in `ConnectionsManager` get
   plain-Java replacements. `FileLoader` itself has no direct tgnet/native
   imports (good) — only `FileLoadOperation` needs the REST download/upload
   adapter.

## 3. Build strategy

- **Track A (sandbox) — DONE, GREEN:** JDK 11 + SDK 33 + `assembleAfatDebug`
  with native tasks excluded → **app.apk 52.1 MB built successfully**
  (`org.telegram.messenger.beta`, versionName 10.14.3, versionCode 49279,
  6 dex files, 0 native libs — not launchable by design; validates the full
  Java/dex/resource/manifest chain). Known sandbox gotchas recorded in
  worklog: AGP auto-installs the pinned NDK (4 GB) when licenses are
  accepted; gradle client stdout can hang while the daemon finishes.
- **Track B (authoritative):** GitHub Actions `assembleAfatDebug` (and later
  `assembleAfatRelease`) with submodules + pinned NDK, artifact upload.
  Public fork → free unlimited standard-runner minutes. Workflow file
  `.github/workflows/build.yml` is written and push-ready: recursive
  submodules, temurin 11, preinstalled runner NDKs removed, `ndk;21.4.7075529`
  installed, arm64-only ABI on push (full 4-ABI via `workflow_dispatch`),
  gradle caching, APK artifact retention 14 days.
- Track A failure modes must never block Track B: CI is the source of truth
  for "does it build".

## 4. Branch layout (post-fork)

```
main                      = pristine upstream master (mirror)
base/upstream-<short-sha> = immutable pin of the commit we forked from
develop                   = integration branch (PR target)
feat/rest-gateway         = ConnectionsManager facade + RestGateway + TlJsonMapper
feat/rest-auth            = LoginActivity interception + AuthStore + refresh
feat/rest-sync            = UpdatePoller + MessagesController.processUpdateFromRest
feat/rest-files           = FileLoadOperation REST adapter
ci/build                  = GitHub Actions workflow (merged first)
```

## 5. Phase 2 task graph (execution order)

```
T0 fork + base pin + CI workflow green on unmodified master   [needs: user PAT]
T1 API_KEYS + Java-only build green locally and in CI
T2 AuthStore (EncryptedSharedPreferences, UserConfig ext)      [independent]
T3 RestGateway + TlJsonMapper core (envelope, errors, JWT 401) [independent]
T4 Auth flow: send-code/verify via REST, LoginActivity wiring  [needs T2,T3]
T5 ConnectionsManager facade switch + EndpointMapper default-deny [needs T3]
T6 UpdatePoller + processUpdateFromRest → dialogs/chat render  [needs T5]
T7 1:1 text chat end-to-end on two devices (acceptance demo)   [needs T4,T6]
T8 RestFileLoader (upload chunks, Range resume)                [needs T5, phase 3 start]
```

Acceptance demo (Phase 2 exit): two installs, code `11111`, private text
messages round-trip ≤ ~2 s foregrounded — identical to `ANDROID_PLAN.md`.

## 6. Inputs required from the user

| # | Need | Why | Blocking? |
|---|---|---|---|
| 1 | GitHub username + PAT (classic, scopes `repo` + `workflow`) — or a manual fork + PAT with push access | fork creation, branch push, CI enablement | **Yes — T0** |
| 2 | `applicationId` + app display name (final values, Phase 5 rename) | avoid churn in BuildConfig/manifest now | No (default `org.telegram.messenger` kept until Phase 5) |
| 3 | Signing keystore: generate fresh (recommended, I create + user archives it) vs user-provided | release builds, Phase 5 | No |
| 4 | OK to run GitHub Actions on the account (free for public repos) | Track B | Yes — T0 |
