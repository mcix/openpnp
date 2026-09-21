# DeltaProto Fork Setup

This repository ([mcix/openpnp](https://github.com/mcix/openpnp)) is DeltaProto's fork of
[openpnp/openpnp](https://github.com/openpnp/openpnp), carrying our machine-specific
modifications (HWGC SMT550 driver, DeltaProto feeder/job importers, DeltaProto panel)
while staying mergeable with upstream.

## Branch model

| Branch | Purpose | Rules |
|---|---|---|
| `deltaproto` | **Default & permanent work branch.** All DeltaProto/HWGC work lives here. | Commit/push normally. Never gets merged away. |
| `main` | Pure mirror of `upstream/main`. | **Never commit to it.** Fast-forward only. Should always show "up to date with openpnp/openpnp:main" on GitHub. |

Upstream note: openpnp/openpnp deleted its old `develop` branch. Their default branch is
`main`; bleeding-edge development happens on their `test` branch. We track `main`.

## Remotes

```
origin    https://github.com/mcix/openpnp.git      (this fork)
upstream  https://github.com/openpnp/openpnp.git   (OpenPnP project)
```

## Syncing with upstream

On `deltaproto`, whenever you want to pull in upstream OpenPnP changes:

```bash
git fetch upstream
git merge upstream/main
```

Merge, don't rebase — `deltaproto` is long-lived and published, so history must stay
stable. Each sync is a single merge commit that can be tested and reverted as a unit.

Optionally keep the `main` mirror badge current afterward:

```bash
git push origin upstream/main:main
```

## Day-to-day work

- Commit directly on `deltaproto` (or short feature branches off it, merged back).
- To see exactly what we've changed versus upstream: `git log main..deltaproto` or
  `git diff main deltaproto`.
- Generic fixes that aren't DeltaProto-specific: branch off `main` and open a PR
  against openpnp/openpnp, then merge upstream back into `deltaproto` normally.
- To keep upstream merges conflict-free, prefer adding new files (like this one) over
  editing upstream files (e.g. README.md) when possible.

## What lives where

DeltaProto-specific code is concentrated in:

- `src/main/java/org/openpnp/machine/hwgc/` — HWGC SMT550 driver, feeder, cameras
- `src/main/java/org/openpnp/machine/hwgc/deltaproto/` — importers, panel, footprints

## Server link (Buddy 2.1 master / Buddy 2.2 slave)

Both machines run the same jar; the role comes from the server
(`/dashboard/parts/buddysettings`), the token from DeltaProto tab → Settings → Server.

| Class | Job |
|---|---|
| `ServerLinkConfig` | server URL, machine token (`X-Buddy-Token`), office-LAN IP detection. Java Preferences, per PC. |
| `ServerLink` | websocket session: CONNECTED → HELLO, PING 30 s / dead after 90 s, backoff 1→30 s, 401 = stop, 403 = retry 60 s, STATUS, peer table. |
| `ServerFeederSync` | debounced feeder refetch on `FEEDER_CONFIG_CHANGED` and every (re)connect; while a job runs only in-place part swaps on lanes the job does not use are applied, the rest waits for pause/stop. |
| `BuddyLanLink` | master → slave TCP link (default port 8765, newline-delimited `{type,value}` JSON; HELLO/PING/PONG/ERROR). The slave only accepts the master's `localIp`. Conveyor/job commands plug in via `setFrameHandler`. |

| `BoardTransfer` | PCB hand-over master → slave: `BOARD_FEED_IN` → slave `BOARD_FEED_IN_ACK` (ready: connected, no job) → master `OUT_BOARD` 0x31, slave `IN_BOARD` 0x30 after the configured delay → `BOARD_FEED_IN_DONE`. No ACK within 3 s = the board stays put. |

`ECHO` / `ECHO_REPLY` is the operator's communication test (Settings → Machine link).

The slave PC needs an inbound Windows Firewall rule for the LAN port.

Local-only artifacts that must never be committed: `META-INF/`, `org/` (leaked jar
extractions in the repo root) and the local `build.bat` / `run-openpnp.bat` scripts.
