# PixelProtect architecture

PixelProtect is split into five responsibilities:

- `model`: immutable audit-domain records and block/entity snapshots.
- `listener`: Paper 26.2 events translated into immutable audit transactions.
- `storage`: durable SQLite/MySQL/MariaDB access, bounded ingestion, batched writes and overflow persistence.
- `service`: attribution, automation correlation, inspection, inventory diffs and guarded rollback orchestration.
- `command`: Paper Brigadier command tree; database work is asynchronous and world mutation remains region-bound.

## Threading contract

The event/region hot path captures immutable values and performs only bounded queue operations. Disk writes for saturated queues happen on the dedicated overflow writer. Database queries and writes run on the storage executor or JDBC pool.

Bukkit/Paper world state is never read from a database completion callback. When an asynchronous lookup needs world state, the callback schedules work back to the affected Paper region. Rollback uses the same rule.

## Audit transaction

A persisted audit entry contains:

1. timestamp
2. world UUID and exact coordinates
3. actor UUID/name when available
4. action type
5. exact Paper `BlockData` before and after
6. inventory snapshots where applicable
7. block-entity snapshots where applicable
8. transaction UUID
9. deterministic sequence number

Entity lifecycle records use the same audit transaction identity and additionally persist an `EntitySnapshot` containing identity, type, position, rotation, velocity, lifecycle flags, item payload and causal metadata.

## Automation attribution

Automation transfers are represented as a causal chain rather than a bare container mutation:

`actor → placed mechanism → source → mechanism → destination → resulting inventory diff`

Hopper search links and mechanism ownership are kept in memory for low-latency correlation. If the owner is absent from memory, placement history is queried asynchronously and the resulting write is returned to the owning region before any Bukkit block access occurs.

## Rollback contract

Rollback is persistent and conflict-aware. Entries are grouped by chunk and dispatched through `RegionScheduler`. A block is mutated only when its live `BlockData`, inventory state and recorded block-entity state match the audit post-state. Entity rollback uses the same present/absent state model and refuses conflicting UUID state.

Every applied entry is persisted in `rollback_job_entries`. Completed jobs can be restored by evaluating the inverse transition with the same conflict guards.

## Storage contract

SQLite uses WAL, foreign keys and a busy timeout. MySQL/MariaDB uses HikariCP. Schema creation/migration is automatic. Rollback references protect audit rows from retention deletion. Overflow replay is serialized with active spool writes so the durable JSONL file cannot be moved while a writer is appending to it.

## Command contract

The only command root is `/pixelprotect`. Lookup, inspector, rollback, restore, purge, status and version operations all use the same asynchronous storage and region-safe mutation architecture.
