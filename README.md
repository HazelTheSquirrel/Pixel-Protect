# PixelProtect

Standalone forensic world history, inventory/container logging, inspection and conflict-safe rollback for Paper 26.2+.

## Runtime

- Paper 26.2 build 121 target.
- Java 25 only.
- Mojang-mapped Paper development bundle; no CraftBukkit or versioned NMS packages.
- Modern `paper-plugin.yml` and Paper lifecycle command registration.
- Folia-safe region scheduling for all asynchronous world-state hand-offs.
- `/pixelprotect` is the only command root.
- SQLite by default; MySQL/MariaDB through HikariCP + Connector/J.
- No PixelRPG or other plugin dependency.

## Logging

PixelProtect records player block placement/breakage and multi-block placement, explosions, fire, growth/spread, fluids, pistons, entity-caused block changes, buckets, cauldrons, modern Paper block mechanics, signs, player interaction, chat, commands, sessions, entity interaction, entity lifecycle, items, projectiles, crafting, trading and container processing.

Audit records contain exact before/after Paper `BlockData`, block-entity state and inventory snapshots where applicable. Multi-location events receive a transaction UUID and deterministic sequence.

Entity records use a defensive Paper `EntitySnapshot` plus runtime and causal metadata. Dedicated entity and inventory audit tables retain the forensic transaction relationship.

Automation logging correlates hopper/dropper/dispenser/crafter mechanisms with source, destination, mechanism location and recorded owner. Owner resolution can fall back to placement history without touching Bukkit state from a database worker thread.

## Inspector

`/pixelprotect inspect` toggles inspector mode. Left- or right-clicking a block performs an asynchronous database lookup and presents actor, action, timestamp, coordinates, inventory deltas and available automation source/destination/mechanism context to the player.

## Rollback

Rollback jobs are persisted, chunk-grouped and executed through Paper's region scheduler. A recorded post-state is verified immediately before every mutation. Inventory and block-entity state are guarded by the same conflict check.

Entity rollback handles both entity-present and entity-absent transitions, recreating recorded non-player entities when their UUID state is conflict-free. Applied entries are persisted so a completed rollback can be restored.

Interrupted `RUNNING` jobs are converted to `FAILED` during startup rather than being treated as successful.

## Storage

The hot audit path uses a bounded in-memory queue. Saturated records are handed to a dedicated overflow writer queue; disk writes therefore never occur on a server event or region thread. Overflow replay is serialized against active spool writes.

SQLite runs with WAL, foreign-key enforcement and a busy timeout. MySQL/MariaDB uses the configured HikariCP pool. Required tables are created automatically.

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

Selectors support actor, time, radius/world/chunk/coordinate restrictions, action inclusion/exclusion and pagination/count/preview controls.

## Build

```text
gradle clean build
```

The release artifact is `build/libs/PixelProtect.jar`.

See `docs/ARCHITECTURE.md` and `PIXELPROTECT_ROADMAP.md` for the final implementation contract and threading model.
