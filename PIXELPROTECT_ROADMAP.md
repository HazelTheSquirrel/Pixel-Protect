# PixelProtect — Final Implementation Contract

Stand: 2026-09-08 — Paper 26.2 / Java 25

PixelProtect is delivered as a standalone forensic audit and rollback plugin. The repository uses `main` as the release line and contains no feature-branch workflow.

## Runtime contract

- Paper 26.2 build 121 target.
- Java 25.
- Mojang-mapped Paper development bundle; no CraftBukkit or versioned NMS packages.
- `paper-plugin.yml` with Paper lifecycle command registration.
- Folia-safe region scheduling for world mutation and asynchronous attribution hand-offs.
- `/pixelprotect` is the sole command root.
- SQLite is the default storage backend.
- MySQL/MariaDB uses HikariCP and Connector/J.
- Audit capture is bounded, asynchronous and durable under queue pressure.

## Forensic data model

Every persisted block audit contains:

- monotonic database id
- millisecond timestamp
- world and exact coordinates
- actor UUID/name where a direct actor exists
- action taxonomy
- exact before/after Paper `BlockData`
- before/after inventory snapshots where applicable
- before/after block-entity state where available
- transaction UUID
- deterministic sequence within a multi-record event

Entity records use `EntitySnapshot` with UUID, type, location, rotation, velocity, lifecycle state, item payload, custom name and causal metadata. Dedicated entity and inventory audit tables retain the transaction relationship.

## Capture coverage

The runtime listeners cover player block placement/breakage, multi-place, explosions, fire, growth and spread, fluids, pistons, entity-caused block changes, attached/natural block mechanics exposed by Paper, buckets, cauldrons, sculk-related mechanics, decay, moisture, dispense/crafter/compost/shear/vault/TNT mechanics, signs, player interaction, commands, chat, sessions, entity interaction, hanging entities, entity damage/death/remove/spawn, item drop/pickup/despawn, projectile hits, crafting, trading, lecterns and inventory/container processing.

Automation attribution tracks hopper/dropper/dispenser/crafter mechanisms, source and destination inventories, hopper search links, mechanism location and player ownership. When an owner is not present in memory, the placement history is resolved asynchronously and the resulting audit write is returned to the owning region before Bukkit state is touched.

## Queue and durability

The hot event path performs bounded queue insertion only. Saturated audit records enter a dedicated overflow writer queue; disk writes occur on the overflow writer thread. Overflow replay is serialized against active spool writes. SQLite uses WAL, foreign keys and a busy timeout. MySQL/MariaDB uses pooled JDBC connections.

## Inspector

Inspector mode performs the database lookup asynchronously and returns presentation to the player scheduler. The inspected world/block state is captured before asynchronous work so no Bukkit block access is performed from the database worker.

Inspector output includes actor, action, timestamp, coordinates, inventory item deltas and automation source/destination/mechanism context when available.

## Rollback

Rollback jobs are persistent and grouped by chunk. World mutations are executed through the Paper region scheduler. Every mutation verifies the recorded post-state before applying the inverse state. Block-entity and inventory state are guarded by the same verification step.

Entity rollback handles both present→absent and absent→present audit transitions, including recreation of recorded non-player entities when a conflict-free UUID state is available. Applied rollback entries are persisted and can be restored through the inverse operation.

Rollback job state survives a server restart; an interrupted `RUNNING` job is persisted as `FAILED` instead of being silently considered successful.

## Storage schema

The current schema contains:

- `pixelprotect_meta`
- `audit`
- `rollback_jobs`
- `rollback_job_entries`
- `rollback_restores`
- `entity_audit`
- `inventory_audit`

The schema is created and migrated automatically by the selected backend.

## Command surface

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

Selectors support actor, time, radius/world/chunk/coordinate restrictions, action inclusion/exclusion, block inclusion/exclusion and pagination/count/preview controls.

## Architecture

`Paper Event → immutable capture → bounded audit queue → durable storage → deterministic query → transaction-aware planner → region-safe mutation → conflict verification → persistent rollback result`

No PixelRPG dependency exists. The plugin is standalone.
