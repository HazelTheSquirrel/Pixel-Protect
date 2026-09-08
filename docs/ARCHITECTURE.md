# PixelProtect — release architecture

Stand: 2026-09-08 · Paper 26.2 build 121 · Java 25

PixelProtect is a standalone forensic audit and recovery system. The release line is `main`; the runtime contains no dependency on PixelRPG or another plugin.

## Execution pipeline

`Paper event → immutable capture → bounded audit queue → durable storage → deterministic query → transaction-aware planner → region-safe mutation → conflict verification → persistent result`

The design has one non-negotiable boundary: Bukkit/Paper world state is only read or mutated on the owning server/region context. Database work never crosses that boundary implicitly.

## Runtime layers

### `model`
Immutable records define audit entries, selectors, actor identity and block/entity snapshots. Snapshots contain only data that can be safely captured and replayed through the public Paper API.

### `listener`
Paper 26.2 events are converted into immutable records immediately. Events that represent actual state transitions carry before/after state. Pure forensic activity carries explicit metadata in `details` and is not classified as a reversible block transition.

### `storage`
SQLite is the default single-writer backend. MySQL/MariaDB uses HikariCP and Connector/J. Schema creation and migration are automatic. Audit ingestion is bounded in memory; saturated entries are durably serialized to a dedicated overflow writer without blocking event or region threads.

### `service`
Attribution, automation correlation, inspection, inventory diffing and rollback planning operate on immutable data. Asynchronous completions that need world state schedule back to the owning region before touching Bukkit objects.

### `command`
The Paper lifecycle API registers the single `/pixelprotect` root. Lookup, inspection, rollback, restore, purge, status and version commands use the asynchronous storage path.

## Audit transaction model

Each audit row contains:

1. monotonic database id
2. millisecond timestamp
3. world UUID and exact coordinates
4. actor UUID/name when available
5. explicit action taxonomy
6. exact before/after Paper `BlockData`
7. before/after inventory snapshots where applicable
8. supported block-entity state where applicable
9. transaction UUID
10. deterministic sequence number
11. forensic `details` for non-state metadata

Entity lifecycle records retain the same transaction identity and store defensive public Paper `EntitySnapshot` information plus identity, location and causal metadata. Dedicated entity/inventory tables preserve the relationship without requiring versioned NMS.

## Automation attribution

Automated transfers are correlated as:

`actor → placed mechanism → source → mechanism → destination → resulting inventory diff`

Mechanism ownership, hopper search links and short-lived transfer contexts are kept in memory. If direct ownership is unavailable, placement history is resolved asynchronously. No database callback reads a live block or inventory; the final world-side correlation is returned to the owning region.

## Snapshot and rollback safety

Block snapshots capture full public Paper block state. Known block-entity types with stable public APIs are encoded explicitly. Unknown non-null block-entity state is treated as unsupported rather than as equal-to-empty, preventing false-positive conflict checks.

Rollback jobs are persisted and grouped by chunk. Each mutation verifies the recorded post-state immediately before applying its inverse. Inventory and block-entity state participate in the same guard. Entity rollback explicitly distinguishes expected-present from expected-absent state and only recreates non-player entities when the live UUID state is conflict-free.

Every applied audit id is persisted in `rollback_job_entries`. A completed rollback can therefore be restored by applying the inverse transition with the same conflict guards. A `RUNNING` job found during startup is marked `FAILED` with an explicit restart error.

## Storage durability

SQLite is configured with WAL, `synchronous=NORMAL`, foreign keys and a busy timeout. MySQL/MariaDB is managed by HikariCP with configurable pool size, idle floor, connection timeout and optional leak detection.

Overflow serialization and replay are protected by one file lock. Shutdown first drains the overflow writer and the database queue before closing the underlying storage connection.

Schema version 8 contains:

- `pixelprotect_meta`
- `audit`
- `rollback_jobs`
- `rollback_job_entries`
- `rollback_restores`
- `entity_audit`
- `inventory_audit`

Retention never removes audit rows referenced by rollback jobs.

## API boundary

The implementation intentionally uses public Paper APIs and Mojang mappings only. There are no CraftBukkit imports, versioned NMS packages or deprecated `UnsafeValues` entity serialization shortcuts. This keeps the persistence contract independent of server internals and limits future-version changes to explicit public-API adapters.

## Release surface

The only command root is `/pixelprotect`. The current command surface is documented in `README.md` and is backed by the same storage, transaction and region-safety contracts described here.
