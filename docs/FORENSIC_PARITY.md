# PixelProtect forensic parity analysis

This document records the repository-level comparison of PixelProtect against the current public CoreProtect codebase and documentation. The comparison target is PlayPro/CoreProtect `master` as inspected in September 2026.

## Executive finding

PixelProtect already has the core forensic pipeline required for a modern Paper 26.2 server: immutable event capture, bounded asynchronous persistence, SQLite/MySQL storage, block/container/inventory/entity records, asynchronous lookup, inspector mode, persistent rollback jobs, conflict checks, restore/undo, retention and a public API. The repository is therefore no longer a basic CoreProtect clone; it is a separate Paper-26.2-native implementation with a richer explicit before/after model.

The principal functional gap was WorldEdit/FAWE attribution. PixelProtect now closes that gap with an optional WorldEdit extent integration. WorldEdit edit sessions are wrapped at `BEFORE_HISTORY`, grouped by transaction UUID, attributed to the WorldEdit actor, and recorded as immutable block transitions without performing JDBC work in the edit thread.

Several CoreProtect features remain intentionally outside the current command surface because PixelProtect's architecture is different: database migration commands, consumer pause/resume, per-world override files, localization packs, update/error reporting, clickable pagination, and CoreProtect's broad database matrix. These are operational/product features rather than missing forensic primitives. PixelProtect's current backend contract remains SQLite plus MySQL/MariaDB.

## Evidence from PixelProtect

The main branch contains dedicated listener layers for block changes, modern block mechanics, player activity, player transactions, inventories, player inventories, entity forensics, container processing, automation, and inspection. The model includes explicit action types and immutable block/entity snapshots. The storage layer is schema-versioned and currently at schema version 8, with rollback jobs, rollback entries, inverse restore jobs, entity audit rows, inventory audit rows, transaction IDs, sequence numbers and forensic details.

The database path uses a bounded in-memory queue and a dedicated database executor. SQLite uses WAL, foreign-key enforcement and a busy timeout. The SQLite runtime is instantiated through `AsyncOverflowDatabase`, whose overflow writer keeps saturated event writes off the event/region thread. MySQL/MariaDB uses HikariCP.

Rollback state is persisted. Running jobs are marked failed after a restart, and rollback application is guarded by the recorded post-state before mutating live world state. This is stronger than a naive "set the old block" rollback because it prevents overwriting a newer unrelated change at the same location.

## CoreProtect comparison

| Capability | PixelProtect | CoreProtect | Result |
|---|---|---|---|
| Block placement/break logging | Yes | Yes | Parity |
| Natural/mechanical block changes | Yes, dedicated mechanics listener set | Yes | Parity |
| Explosions/fire/fluids/growth/pistons | Yes | Yes | Parity |
| Container transactions | Yes | Yes | Parity |
| Player inventory audit | Yes, separate inventory records | Yes | Parity, stronger separation |
| Item pickup/drop/despawn | Yes | Yes | Parity |
| Entity spawn/death/damage/removal | Yes | Yes | Parity |
| Projectile and entity interaction | Yes | Yes | Parity |
| Chat and command auditing | Yes | Yes | Parity |
| Session auditing | Login/logout/world change | Login/logout/session data | Parity for core session history |
| Sign auditing | Yes | Yes | Parity |
| Inspector | Async database lookup | Yes | Parity; PixelProtect is explicitly async |
| Radius lookup | Yes | Yes | Parity |
| Actor/time/action/block filters | Yes | Yes | Parity for implemented selector grammar |
| Count/preview/verbose/silent selectors | Count/preview/verbose/silent | Yes | Parity |
| Paginated lookup | Yes | Yes | Parity; clickable UI is not yet part of PixelProtect |
| Persistent rollback jobs | Yes | Yes | Parity |
| Restore/undo | Yes | Yes | Parity |
| Rollback conflict protection | Explicit recorded post-state check | CoreProtect uses rollback caches/change handling | PixelProtect has explicit state guard |
| Entity rollback | Expected-present/expected-absent model | Yes | Parity in architecture |
| WorldEdit logging | **Implemented through WorldEdit EditSession extent** | Yes | Parity |
| FAWE-compatible extent interception | Extent-level interception, provided the FAWE stack exposes the same WorldEdit event path | Supported by CoreProtect | Architectural parity |
| SQLite | Yes | Yes | Parity |
| MySQL/MariaDB | Yes, HikariCP | Yes | Parity |
| DuckDB | No | Yes | CoreProtect advantage |
| ClickHouse | No | Yes | CoreProtect advantage |
| Automatic database migration command | No | Yes | CoreProtect advantage |
| Consumer pause/resume command | No | Yes | CoreProtect advantage |
| `/co reload` equivalent | No | Yes | CoreProtect advantage |
| Per-world override config files | Include/exclude world sets | Yes | CoreProtect advantage |
| Localization system | German messages in code | Yes | CoreProtect advantage |
| Automatic update/error reporting | No | Yes | CoreProtect advantage |
| Clickable pagination | No | Yes | CoreProtect advantage |
| Advanced lookup permissions by action type | Command-level permissions | Fine-grained lookup permissions | CoreProtect advantage |
| Public developer API | Yes | Yes | Parity in principle; different API contract |

CoreProtect's command documentation confirms lookup/rollback/restore selectors for users, time, radius, action, include/exclude filters and hashtags such as `#preview`, `#count`, `#verbose` and `#silent`. It also provides `/co near`, `/co undo`, `/co reload`, `/co consumer` and `/co migrate-db`. PixelProtect currently covers the forensic command core but deliberately exposes only `/pixelprotect` as its command root.

CoreProtect's current README also lists WorldEdit/FAWE logging, inventory rollback, multi-world configuration, advanced lookup permissions, localization, automatic update/error reporting and multiple database backends. Those operational features form the remaining product-level gap after the WorldEdit integration was added.

## WorldEdit implementation

PixelProtect now depends on WorldEdit 7.4.4 as an optional compile-time integration and keeps it out of the shaded runtime artifact. The plugin descriptor already declares WorldEdit as an optional server dependency.

The integration uses WorldEdit's public `EditSessionEvent` and wraps the `BEFORE_HISTORY` extent. The wrapper captures the immutable WorldEdit `BaseBlock` before and after each successful `setBlock`, converts the block state through `BukkitAdapter`, and writes the transition through the same PixelProtect audit queue used by native Paper listeners. A single edit session receives one transaction UUID and deterministic sequence numbers.

Actor attribution uses WorldEdit's public `Actor` identity. Console/non-player edits are recorded as the environment actor. Tile-entity NBT is retained as forensic detail where WorldEdit supplies it. No database operation is performed directly from the WorldEdit extent.

## Data-model differences

PixelProtect intentionally separates reversible world state from forensic activity. A chat message, command, session event, interaction or player inventory transition can therefore be queried without being interpreted as a block mutation during rollback. Container and inventory data have dedicated audit tables, while block rows keep before/after block state, inventory snapshots where applicable, block-entity snapshots, transaction IDs, sequence numbers and details.

This separation is important for safe rollback. A global rollback cannot accidentally replace a player's current inventory merely because an inventory audit record happened to be inside the selected time window.

## Performance model

CoreProtect emphasizes asynchronous/multi-threaded processing. PixelProtect follows the same principle but makes the boundary explicit:

1. Paper event/region thread captures immutable state.
2. The record is offered to a bounded queue.
3. A dedicated database executor batches and persists records.
4. If the queue is saturated, the overflow implementation serializes records and hands them to a dedicated overflow writer.
5. Database queries run asynchronously.
6. World mutation during rollback is returned to the appropriate Paper scheduler.

SQLite is opened in WAL mode and uses a busy timeout. MySQL/MariaDB is pooled through HikariCP. The resulting architecture prevents JDBC latency from being placed directly into block/event handlers.

## Security and correctness model

PixelProtect's rollback engine does not treat historical state as authoritative over newer live state. It compares the live block against the recorded post-state before applying the recorded pre-state. This protects against a rollback overwriting a newer player change at the same coordinate.

Rollback jobs and restore jobs are persisted. A server restart while a job is running does not silently convert the job into success. This makes forensic recovery auditable and gives administrators a stable job identifier for status, cancellation and inverse restoration.

## Current command surface

- `/pixelprotect`
- `/pixelprotect help`
- `/pixelprotect version`
- `/pixelprotect status`
- `/pixelprotect inspect`
- `/pixelprotect lookup <radius> <hours> [selectors...]`
- `/pixelprotect near [selectors...]`
- `/pixelprotect rollback <radius> <hours> [selectors...]`
- `/pixelprotect rollback status <job>`
- `/pixelprotect rollback cancel <job>`
- `/pixelprotect restore <job>`
- `/pixelprotect undo <job>`
- `/pixelprotect purge <days>`

Selectors include actor, duration, radius, world, coordinates, chunks, action include/exclude, block include/exclude, page, count, preview, verbose and silent modes. Block actions use `+block` for placement and `-block` for breaking, matching CoreProtect's established operator semantics.

## Reference sources

- PlayPro/CoreProtect README and feature inventory: https://github.com/PlayPro/CoreProtect
- CoreProtect command reference: https://github.com/PlayPro/CoreProtect/blob/master/docs/commands.md
- CoreProtect configuration reference: https://github.com/PlayPro/CoreProtect/blob/master/docs/config.md
- CoreProtect API documentation: https://github.com/PlayPro/CoreProtect/blob/master/docs/api/index.md
- WorldEdit edit-session API: https://worldedit.enginehub.org/en/7.3.19/api/concepts/edit-sessions/
- WorldEdit 7.4.4 `AbstractDelegateExtent` API: https://docs.enginehub.org/javadoc/com.sk89q.worldedit/worldedit-core/release/com/sk89q/worldedit/extent/AbstractDelegateExtent.html
- WorldEdit 7.4.4 `NbtValued` API: https://docs.enginehub.org/javadoc/com.sk89q.worldedit/worldedit-core/release/com/sk89q/worldedit/world/NbtValued.html
