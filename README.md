# PixelProtect

A standalone, audit-first world history and rollback system for Paper 26.2+.

## Runtime contract

- Paper 26.x only; development and CI target is Paper 26.2 build 121.
- Java 25 only.
- `paper-plugin.yml` and Paper plugin lifecycle commands.
- Folia-safe region scheduling for world mutation.
- `/pixelprotect` is the only command root; no legacy aliases.
- SQLite is the default backend. MySQL/MariaDB is available through the HikariCP JDBC backend.
- Immutable audit records, transaction IDs, deterministic selectors and guarded rollback/restore jobs.
- No dependency on PixelRPG or any other plugin.

## Storage

SQLite is configured by default in `config.yml`. Set `storage.backend` to `mysql` or `mariadb` and configure `storage.mysql.*` to use the pooled JDBC backend. The schema and rollback tables are created automatically and use the same schema version contract.

The audit queue is bounded and spills records to a durable JSONL file when the queue is saturated or the database temporarily rejects a batch. Overflow records are replayed on the next flush/startup.

## Build

```text
gradle build
```

The resulting plugin is `build/libs/PixelProtect.jar`.

## Core

The core records player block changes, explosions, fire, growth/spread, fluids, pistons, entity-caused block changes, entity lifecycle/item/projectile events and inventory activity. Records carry transaction identity and sequence information where an event affects multiple locations. Block records contain exact Paper `BlockData`, inventory snapshots and guarded block-entity state. Entity records use Paper `EntitySnapshot` plus runtime/cause attribution. Rollback is region-scheduled and checks the recorded post-state before mutating the world.

See `PIXELPROTECT_ROADMAP.md` and `docs/ARCHITECTURE.md` for the detailed data/threading contracts.
