# backend-ops
GitHub Actions proxy for the live Hermes PHP backend on xorbit.ir: the sandbox egress IP is WAF-blocked, but GitHub-hosted runners can reach the cPanel WebDAV endpoint and the public API.
Two ops via `workflow_dispatch` input `op` on `.github/workflows/backend-ops.yml`:
- `pull` — snapshot the live tree (`/app`, `/config`, `/public_html/tele`) with Depth:1 WebDAV walks into the `live-backend` artifact + baseline API smoke.
- `patch` — deploy `.github/backend-ops/live/*` with `.bak_pre_v293` pre-backups, then run the verify smoke suite.
Required repo secrets: `WD_URL`, `WD_USER`, `WD_PASS` (WebDAV Basic auth; WebDAV root = server home dir).
`live/` mirrors the server layout: `app/` -> `/app`, `public/` -> `/public_html/tele`, `config/` -> `/config`.
`pull_live.py` writes `_inventory.txt` (path + size) and skips files > 5MB.
Smoke uses a browser User-Agent (plain curl UA is WAF-killed); base URL overridable via `API_BASE`.
Dispatch from the Actions tab or the REST API with `ref: infra/backend-ops`.
