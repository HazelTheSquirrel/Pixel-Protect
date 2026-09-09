# Pixel-Protect

Pixel-Protect is a standalone, Paper 26.2-native forensic logging and anti-griefing platform. It records world changes, containers, player inventories, entities and player activity, then provides asynchronous inspection, lookup, conflict-safe rollback, restore and retention workflows.

Pixel-Protect is an independent project with its own architecture, data model, command model and implementation. It uses only the public APIs and libraries explicitly declared by this repository.

The project is deliberately independent of PixelRPG. The primary command is `/pixelprotect` and `/pp` is its short alias.

## Project principles

- **Simple for players:** the inspector shows only the useful answer, not database or forensic internals.
- **Independent implementation:** Pixel-Protect has its own data model and implementation.
- **Public APIs only:** Paper APIs, Mojang mappings and explicitly declared public integration APIs are used; no CraftBukkit or versioned NMS internals.
- **Forensic first:** historical records are immutable evidence, while live world state remains authoritative for conflict-safe recovery.
- **Asynchronous by design:** database work and expensive analysis never run inside the event/region thread.

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

Pixel-Protect records player and environmental changes including block placement/breaking, explosions, TNT, fire, fluids, pistons, growth, entities, buckets, containers, inventories, automation, signs and other supported modern Paper mechanics. WorldEdit sessions are also captured when WorldEdit is installed.

Container transactions retain before/after inventory snapshots. This makes it possible to answer the important question directly: **who put what in, and who took what out?**

## Inspector

Use:

```text
/pp inspect
```

Then **right-click the block** you want to check. Pixel-Protect deliberately gives you **one compact chat message for that block**. It does not print UUIDs, transaction IDs, database details or a technical audit dump.

Examples:

```text
PixelProtect • 125 / 64 / -32
Block: Chest
Inhalt geändert:
Rausgenommen von Alex: 32× Diamant, 12× Eisen
Reingelegt von Steve: 64× Stein, 8× Gold
```

For a normal block:

```text
PixelProtect • 125 / 64 / -32
Block: Oak Planks
Platziert von: Steve • vor 2 Stunden
```

If the block was later broken, the inspector reports the latest relevant state:

```text
PixelProtect • 125 / 64 / -32
Block: Air
Abgebaut von: Alex • vor 15 Minuten
```

Inspector mode also prevents the right-click from accidentally opening a chest, pressing a button, placing an item or otherwise changing the world. Turn it off with `/pp inspect` again.

The lookup itself remains asynchronous. The player only sees the final useful result.

## Lookup

The simple forms are enough for normal administration:

```text
/pp lookup
/pp lookup <Radius>
/pp lookup <Radius> <Stunden>
/pp near
/pp log <Radius> <Stunden>
```

Advanced filters are still available when needed:

```text
u:<Spieler>       Spieler
 t:<Dauer>        Zeitfenster
r:<Radius>        Radius
w:<Welt>          Welt
c:x,y,z           Mittelpunkt
ch:x,z            Chunk
 a:<Aktion>       Aktion
 a:+block         nur Platzieren
 a:-block         nur Abbauen
i:<Block>         Block einschließen
e:<Block>         Block ausschließen
#page:n           Seite
#count            nur Anzahl
```

Durations support `30s`, `30m`, `12h`, `7d`, `1w` and decimal values such as `2.5h`.

`/pp log` is simply a short alias for `/pp lookup`.

## Rollback and restore

The common commands are:

```text
/pp rollback
/pp rollback <Radius>
/pp rollback <Radius> <Stunden>
/pp rollback <Radius> <Stunden> #preview
/pp rollback status <Auftrag>
/pp rollback cancel <Auftrag>
/pp restore <Auftrag>
/pp undo <Auftrag>
```

A rollback is a persisted asynchronous recovery job. Database selection happens off-thread; world mutations are scheduled through Paper's region/global scheduler. Before a mutation is applied, the live post-state is checked against the recorded state so newer changes are not silently overwritten.

`/pp restore <Auftrag>` and `/pp undo <Auftrag>` apply the inverse transition of a completed rollback job.

Player inventory records are deliberately excluded from block rollback. Inventory history remains available for forensic lookup.

## Commands

```text
/pp                         help
/pp help                    help
/pp version                 version
/pp status                  storage/runtime status
/pp inspect                 inspector on/off
/pp check                   inspector on/off alias
/pp lookup [Radius] [Std]   history lookup
/pp log [Radius] [Std]      lookup alias
/pp near                    lookup around you
/pp rollback [Radius] [Std] rollback
/pp rollback status <ID>   job status
/pp rollback cancel <ID>   cancel job
/pp restore <ID>            restore/invert a job
/pp undo <ID>               restore/invert alias
/pp purge <Tage>            delete old history
```

The command tree is registered through the modern Paper lifecycle command API. There is no legacy `plugin.yml` command registration.

Permissions:

```text
pixelprotect.status
pixelprotect.inspect
pixelprotect.lookup
pixelprotect.rollback
pixelprotect.purge
```

## Storage and durability

The hot event path is bounded and non-blocking:

1. A Paper event captures immutable state.
2. The record is offered to the bounded in-memory queue.
3. A dedicated database worker batches writes.
4. Saturated queues use the asynchronous overflow writer.
5. Queries run asynchronously.
6. Rollback world mutations return to Paper scheduling APIs.

SQLite uses WAL mode, foreign-key enforcement and a busy timeout. MySQL/MariaDB uses HikariCP with a configurable pool.

The current schema is version 8 and is migrated automatically on startup. It contains audit history, rollback jobs, rollback job entries, restore jobs, entity audit records, inventory audit records, transaction sequencing and forensic details.

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

MySQL/MariaDB can be selected with `storage.backend` and the `storage.mysql.*` settings. World logging can be restricted through `worlds.include` and `worlds.exclude`. Retention and diagnostic intervals are configurable.

## Build and verification

```text
gradle clean build
```

The shaded release artifact is:

```text
build/libs/PixelProtect.jar
```

The build targets Java 25 and the Paper 26.2 build 121 development bundle. CI validates the source API boundary, project independence, legacy descriptor absence and shaded artifact structure. CI intentionally does not download or boot a Paper server; runtime server provisioning belongs to the deployment environment.

## Architecture

See `docs/ARCHITECTURE.md` for the threading, persistence and rollback model.
