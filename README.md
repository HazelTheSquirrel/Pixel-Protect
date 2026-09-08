# PixelProtect

PixelProtect is a standalone forensic world-history, inventory/container audit, inspection and conflict-safe rollback plugin for Paper 26.2+.

## Release contract

- Paper 26.2 build 121 target.
- Java 25 only.
- Mojang-mapped Paper development bundle; no CraftBukkit or versioned NMS packages.
- Modern `paper-plugin.yml` and Paper lifecycle command registration.
- Folia-safe region scheduling for every asynchronous hand-off that touches world state.
- `/pixelprotect` is the only command root.
- SQLite is the default backend; MySQL/MariaDB is supported through HikariCP and Connector/J.
- No PixelRPG or other plugin dependency.

## Forensic logging

PixelProtect records player block placement/breakage and multi-block placement, explosions, fire, growth/spread, fluids with source coordinates, pistons, entity-caused block changes, buckets, cauldrons, modern Paper block mechanics, signs, player interaction, chat, commands, sessions, entity interaction, entity lifecycle, entity damage/death causality, items, projectiles, crafting, trading and container processing.

Player inventory transitions are recorded as a dedicated `INVENTORY` action so player-owned inventory history is queryable without ever being treated as a block rollback transition. Container transactions remain `CONTAINER` records and retain their location-based reversible state.

Persisted audit entries contain exact before/after Paper `BlockData`, block-entity state and inventory snapshots where applicable. Multi-location events receive one transaction UUID with deterministic sequence numbers. Forensic metadata is stored separately from reversible block state so non-mutating activity cannot be mistaken for a rollback transition.

Entity records use defensive public Paper `EntitySnapshot` data plus runtime and causal metadata. Dedicated entity and inventory audit tables preserve the transaction relationship.

Automation attribution correlates hopper/dropper/dispenser/crafter mechanisms with source, destination, mechanism location and recorded owner. Owner resolution can fall back to placement history without reading Bukkit world state from a database worker.

## Inspector

`/pixelprotect inspect` toggles inspector mode. Left- or right-clicking a block captures the relevant immutable coordinates and performs the database lookup asynchronously. Results are returned through the appropriate scheduler and include actor, action, timestamp, coordinates, inventory deltas and available automation context.

## Rollback and restore

Rollback jobs are persisted, chunk-grouped and executed through Paper's region scheduler. Before each mutation, the live post-state is compared with the recorded post-state. BlockData, inventory and supported block-entity state are guarded together; unsupported block-entity restoration never silently succeeds.

Player `INVENTORY` records are deliberately excluded from block rollback. They remain available to forensic lookup and future dedicated player-inventory recovery workflows, preventing a world rollback from accidentally replacing a player's current inventory.

Entity rollback distinguishes expected-present and expected-absent state and recreates recorded non-player entities only when the live state is conflict-free. Applied entries are persisted, allowing a completed rollback to be restored through its inverse transition.

Interrupted `RUNNING` jobs are persisted as `FAILED` during startup instead of being treated as successful.

## Storage and durability

The hot audit path performs bounded, non-blocking in-memory queue insertion. Saturated records are serialized and handed to a dedicated overflow writer. Event and region threads never perform overflow disk I/O. Overflow replay is serialized against active spool writes.

SQLite uses WAL, foreign-key enforcement and a busy timeout. MySQL/MariaDB uses a HikariCP-managed connection pool and automatic schema creation/migration. The current schema is version 8.

## Commands

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
- `/pixelprotect purge <days>`

Selectors support actor, time, radius/world/chunk/coordinate restrictions, action inclusion/exclusion, block inclusion/exclusion and pagination/count/preview controls. `a:inventory` targets player inventory audit records; `a:container` targets reversible block-container transactions.

## Build

```text
gradle clean build
```

The release artifact is `build/libs/PixelProtect.jar`.

The implementation contract and threading model are documented in `docs/ARCHITECTURE.md`.
