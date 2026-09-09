# Pixel-Protect

Pixel-Protect is a standalone, Paper 26.2-native forensic logging and anti-griefing platform. It records world changes, containers, player inventories, entities and player activity, then provides asynchronous inspection, lookup, conflict-safe rollback, restore and retention workflows.

Pixel-Protect is an **independent project with its own architecture, data model, command model and implementation**. It is not a fork, port, compatibility layer or source-derived implementation of another audit plugin. The project is developed from its own requirements and uses only the public APIs and libraries explicitly declared by this repository.

The project is deliberately independent of PixelRPG. The primary command is `/pixelprotect` and `/pp` is its short alias.

## Project principles

Pixel-Protect is designed around a few hard boundaries:

- **Independent implementation:** no source code, copied implementation details or proprietary assets from third-party audit plugins are used.
- **Public APIs only:** Paper APIs, Mojang mappings and explicitly declared public integration APIs are used; no CraftBukkit or versioned NMS internals.
- **Forensic first:** historical records are immutable evidence, while live world state remains authoritative for conflict-safe recovery.
- **Asynchronous by design:** database work and expensive analysis never run inside the event/region thread.
- **Own data model:** Pixel-Protect's audit records, transaction model, rollback jobs, selectors and persistence schema are designed specifically for this project.

## Platform contract

| Component | Version / contract |
|---|---|
| Minecraft server | Paper 26.2 |
| Paper build target | 26.2.build.121-stable |
| Java | 25 |
| Mappings | Mojang mappings through Paperweight |
| Plugin descriptor | `paper-plugin.yml` |
| Command root | `/pixelprotect` with `/pp` alias |
| Default database | SQLite |
| External database | MySQL / MariaDB via HikariCP + Connector/J |
| Optional integration | WorldEdit 7.4.4 |
| Runtime dependencies | Gson 2.13.1, HikariCP 7.0.2, MySQL Connector/J 9.7.0 |

No CraftBukkit packages, versioned NMS packages or legacy `plugin.yml` command registration are used.

## What is logged

### World and block changes

Pixel-Protect records player and environmental changes including:

- block placement and breaking
- explosions and TNT priming
- fire and burning
- fluids and liquid source attribution
- piston movement
- growth, spread, decay and modern block mechanics
- entity-caused block changes
- buckets and cauldrons
- structure/portal and modern Paper block mechanics
- signs and block-entity state
- flower pots, campfires, lecterns, bookshelves and vault-related activity
- WorldEdit edit sessions

Each reversible block record can retain before/after `BlockData`, block-entity state, inventory snapshots where applicable, forensic details, transaction UUID and deterministic sequence number.

### Containers and inventories

Container transactions are recorded separately from player inventory transitions. The audit model can therefore answer both:

- who moved an item through a chest/container
- what changed in a player's own inventory

Hopper, dropper, dispenser and crafter-style automation is correlated through the automation tracker where the event provides enough causal information.

### Entities and items

The forensic model covers entity spawn/removal/death/damage, projectiles, dropped items, pickup/despawn events and player-caused entity activity. Entity snapshots retain public Paper state plus causal metadata without relying on CraftBukkit or versioned NMS internals.

### Player activity

Pixel-Protect records sessions, world changes, commands, chat, interactions, entity interactions, sign edits and related player activity. Activity records are intentionally not treated as block rollback transitions.

## WorldEdit integration

WorldEdit is optional. When installed, Pixel-Protect registers a public WorldEdit `EditSessionEvent` extent wrapper at the history stage. The wrapper captures successful block transitions, attributes them to the WorldEdit actor, groups the edit session under a transaction UUID and sends the resulting records through the normal Pixel-Protect audit queue.

The integration is compile-time optional and is not shaded into Pixel-Protect. This keeps the standalone plugin usable when WorldEdit is absent while providing the same forensic path when WorldEdit is installed.

The extent-level integration can also observe WorldEdit-based asynchronous editing stacks that preserve the public WorldEdit edit-session event path, subject to the integration behavior of the installed editing stack.

## Inspector

```text
/pixelprotect inspect
/pp inspect
```

Inspector mode is player-local. Left- and right-clicking a block captures only the immutable location required for the query. The database lookup is performed asynchronously and the result is returned through the appropriate Paper scheduler.

Inspector output includes the actor, UUID, action, timestamp, exact before/after block state, cause, transaction and available automation context. For blocks with inventories it now shows the exact occupied slots and item amounts **before** and **after** the recorded event, followed by an explicit net item change list. Breaking a container therefore shows exactly what was inside before it was removed; placing or changing a container shows what was present before and after the change.

## Lookup

```text
/pixelprotect lookup <radius> <hours> [selectors...]
/pp lookup <radius> <hours> [selectors...]
/pixelprotect near [selectors...]
/pp near [selectors...]
```

Supported selectors include:

| Selector | Purpose |
|---|---|
| `u:<user>` | Restrict by actor |
| `t:<duration>` | Restrict by time |
| `r:<radius>` | Radius restriction |
| `w:<world>` | World restriction |
| `c:x,y,z` | Exact center coordinate |
| `ch:x,z` | Chunk center |
| `a:<action>` | Include an action group/type |
| `a:+block` | Placement only |
| `a:-block` | Breaking only |
| `i:<blocks>` | Include block types |
| `e:<blocks>` | Exclude block types |
| `#page:n` | Select result page |
| `#count` | Return only the matching count |
| `#preview` | Preview a rollback |
| `#verbose` | Verbose operation output |
| `#silent` | Minimal operation output |

Durations support seconds, minutes, hours, days, weeks and decimal values such as `2.5h`.

## Rollback and restore

```text
/pp rollback <radius> <hours> [selectors...]
/pp rollback status <job>
/pp rollback cancel <job>
/pp restore <job>
/pp undo <job>
```

Rollback is a persisted asynchronous recovery job. Database selection happens off-thread; world mutations are scheduled through Paper's region/global scheduler.

Before a mutation is applied, Pixel-Protect checks the live post-state against the state recorded by the audit entry. If another change has already altered that location, the historical mutation is skipped rather than overwriting newer world state.

Rollback jobs retain processed/applied/skipped counters and can be restored through their inverse transition. Jobs that were running during a server restart are marked failed instead of being silently reported as successful.

Player `INVENTORY` records are deliberately excluded from block rollback. Inventory history remains available for forensic lookup, preventing a world rollback from replacing a player's current inventory.

## Storage and durability

The hot event path is bounded and non-blocking:

1. A Paper event captures immutable state.
2. The record is offered to the bounded in-memory queue.
3. A dedicated database worker batches writes.
4. Saturated queues use the asynchronous overflow writer.
5. Queries run asynchronously.
6. Rollback world mutations return to Paper scheduling APIs.

SQLite uses WAL mode, foreign-key enforcement and a busy timeout. MySQL/MariaDB uses HikariCP with a configurable pool.

The current schema is **version 8** and is migrated automatically on startup. The schema contains the audit history, rollback jobs, rollback job entries, restore jobs, entity audit records, inventory audit records, transaction sequencing and forensic details.

## Commands

```text
/pixelprotect
/pp
/pixelprotect help
/pp help
/pixelprotect version
/pixelprotect status
/pixelprotect inspect
/pixelprotect lookup <radius> <hours> [selectors...]
/pixelprotect near [selectors...]
/pixelprotect rollback <radius> <hours> [selectors...]
/pixelprotect rollback status <job>
/pixelprotect rollback cancel <job>
/pixelprotect restore <job>
/pixelprotect undo <job>
/pixelprotect purge <days>
```

Permissions currently exposed by the command tree are:

```text
pixelprotect.status
pixelprotect.inspect
pixelprotect.lookup
pixelprotect.rollback
pixelprotect.purge
```

## Configuration

The default configuration uses SQLite:

```yaml
storage:
  backend: sqlite
  file: pixelprotect.db
  queue-capacity: 10000
  batch-size: 256
  flush-interval-millis: 250
```

MySQL/MariaDB can be selected with `storage.backend` and the `storage.mysql.*` settings. World logging can be restricted through `worlds.include` and `worlds.exclude`. Retention and diagnostic intervals are configurable without changing the logging architecture.

## Build and verification

```text
gradle clean build
```

The shaded release artifact is:

```text
build/libs/PixelProtect.jar
```

The build targets Java 25 and the Paper 26.2 build 121 development bundle. CI validates the source API boundary, project independence, legacy descriptor absence and shaded artifact structure. CI intentionally does not download or boot a Paper server; runtime server provisioning belongs to the deployment environment rather than the plugin build pipeline.

## Architecture

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the threading, persistence and rollback model.

## Project identity

Pixel-Protect is intended to stand on its own as an independent Minecraft forensic and recovery system. Similarities in broad functionality are a consequence of solving the same general server-administration problem; the implementation, internal abstractions, persistence model and project documentation are maintained independently.

For contributors, the rule is simple: **do not copy source code, implementation-specific text, proprietary assets or non-public material from other projects into Pixel-Protect.** When researching a capability, derive the requirement independently and implement it against the current Paper/WorldEdit public APIs and Pixel-Protect's own architecture.

## References

- [Paper](https://papermc.io/)
- [WorldEdit](https://github.com/EngineHub/WorldEdit)
- [WorldEdit edit-session API](https://worldedit.enginehub.org/en/7.3.19/api/concepts/edit-sessions/)
