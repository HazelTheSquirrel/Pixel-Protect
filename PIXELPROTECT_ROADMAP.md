# PixelProtect Roadmap / Forensic Completion Matrix

Stand: 2026-09-08 — Paper 26.2 / Java 25

Legende: **DONE** = implementiert und CI-validiert, **PARTIAL** = implementiert, aber noch nicht forensisch vollständig, **OPEN** = noch offen.

## P0 — Forensic correctness

- **PARTIAL** Transaction identity / sequence in audit records
- **PARTIAL** Durable audit queue with disk overflow spool
- **PARTIAL** BlockEntity snapshots (inventories, signs, skull/spawner state; additional TileState adapters still open)
- **PARTIAL** Entity snapshots using Paper `EntitySnapshot` plus exact runtime metadata
- **PARTIAL** Entity rollback / recreation with conflict guards; exact UUID-preserving recreation across restart remains open with public Paper APIs
- **PARTIAL** Rollback job persistence and restart recovery
- **PARTIAL** Rollback failure status handling and compensation within a region group
- **OPEN** Cross-region atomic transaction coordinator with guaranteed global compensation
- **OPEN** Full integration test server for restart/rollback recovery
- **OPEN** Queue/batch failure, corruption and overflow integration tests

## P1 — High-priority forensic coverage

- **DONE** Rich selector grammar including world/chunk/coordinate selectors
- **DONE** Lossless selector duration precision (`s/m/h/d`)
- **DONE** Lookup pagination
- **DONE** Rollback pagination uses the selected page
- **PARTIAL** Multi-block transaction IDs and region-safe scheduling
- **PARTIAL** Piston transaction grouping; complete piston/slime/honey/head/base semantics remain open
- **PARTIAL** Entity event coverage (spawn/death/remove/drop/pickup/despawn/projectile)
- **OPEN** Dedicated entity audit records wired end-to-end to the originating audit transaction
- **OPEN** Dedicated inventory transaction/diff records wired end-to-end
- **OPEN** Full entity cause attribution (spawn reason, death cause, remove cause, projectile hit/shooter)
- **PARTIAL** BlockEntity restore guards
- **OPEN** Full adapters for banners, beacons, beehives, decorated pots, lecterns, jukeboxes, trial spawners, vaults and other current TileState types
- **OPEN** Bucket/cauldron transaction model
- **OPEN** Portal/structure transaction model
- **OPEN** Inventory slot-level transaction diffs

## P2 — Production storage and operations

- **DONE** SQLite schema versioning and migrations through schema v7
- **DONE** SQLite WAL / foreign-key enforcement / busy timeout
- **DONE** Durable overflow spool
- **PARTIAL** Query/index hardening
- **OPEN** Storage backend abstraction
- **OPEN** MySQL/MariaDB backend using the existing HikariCP + Connector/J dependencies
- **OPEN** Configurable include/exclude worlds
- **OPEN** Retention maintenance metrics
- **OPEN** Operational diagnostics / health metrics
- **OPEN** Database corruption/integrity recovery workflow

## P3 — Ecosystem / API / verification

- **OPEN** Protection-plugin attribution hooks
- **OPEN** Optional WorldGuard/Lands/other region integration adapters
- **OPEN** Public PixelProtect API
- **OPEN** Stable transaction/event API for external integrations
- **OPEN** Paper 26.2 integration test server and scenario suite
- **OPEN** Performance/load benchmark suite
- **OPEN** Full migration compatibility suite (v1 → current)

## Command contract

The only command root is `/pixelprotect`.

No `/co`, `/pp` or other aliases are part of the project contract.

## Architecture target

`Paper Event -> Transaction Capture -> Audit Queue -> Durable Storage -> Query/Planner -> Region-safe Rollback -> Compensating Recovery`

Entity, inventory and block-entity records must converge on the same transaction identity before the forensic implementation is considered complete.

PixelRPG is not a dependency of any phase.
