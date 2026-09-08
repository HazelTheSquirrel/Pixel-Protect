# PixelProtect roadmap

## Phase 1 — trustworthy block core

- [x] Paper 26.2 / Java 25 build baseline
- [x] Paper plugin metadata
- [x] Brigadier command registration
- [x] asynchronous SQLite persistence
- [x] batched audit writes
- [x] player block place/break
- [x] explosions and fire
- [x] growth, spread and fluid movement
- [x] piston movement
- [x] entity-caused block changes
- [x] container inventory snapshots
- [x] guarded rollback
- [x] retention purge

## Phase 2 — CoreProtect-level transaction coverage

- [ ] inventory click/drag/transport transactions
- [ ] item entity spawn/pickup/despawn attribution
- [ ] entity death/damage and projectile attribution
- [ ] block entity state beyond inventories
- [ ] bucket interactions and cauldron state
- [ ] signs, books, lecterns, skulls and special tile state
- [ ] portal and structure transactions
- [ ] more precise piston/slime movement transaction grouping

## Phase 3 — query and rollback engine

- [ ] rich selector grammar (`t:`, `r:`, `u:`, `i:`, `a:` style)
- [ ] world/chunk/coordinate selectors
- [ ] preview/dry-run
- [ ] rollback job IDs, progress and cancellation
- [ ] deterministic transaction grouping
- [ ] inverse transaction records
- [ ] per-action filters and block filters

## Phase 4 — production operations

- [ ] configurable include/exclude worlds
- [ ] retention scheduler with maintenance metrics
- [ ] database migrations and schema versioning
- [ ] MySQL/MariaDB backend
- [ ] operational metrics and diagnostics
- [ ] automated integration test server

## Phase 5 — ecosystem integrations

- [ ] optional protection-plugin attribution hooks
- [ ] optional public API
- [ ] optional region/protection integrations

PixelRPG is not a dependency of any phase.
