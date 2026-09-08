# PixelProtect architecture

PixelProtect is intentionally split into four responsibilities:

- `model`: immutable audit-domain records and snapshots.
- `listener`: Paper events translated into audit transactions.
- `storage`: one-thread SQLite access with bounded ingestion and batched writes.
- `service`: audit recording and guarded rollback orchestration.
- `command`: Paper Brigadier command tree; command work never performs SQLite queries on a world thread.

## Threading contract

World state is read and changed only from the owning Paper region thread. Database work is confined to the database executor. Rollback queries are asynchronous and mutations are grouped by chunk and dispatched through the region scheduler.

## Audit transaction

Each block transaction stores:

1. timestamp
2. world UUID and coordinates
3. actor UUID/name when available
4. action type
5. complete `BlockData` string before the change
6. complete `BlockData` string after the change
7. inventory contents before/after for inventory-bearing block states

Rollback is conditional: the live block must still match the recorded post-change state, and when an inventory snapshot exists the live inventory must also match it. This prevents overwriting unrelated changes made after the audited transaction.

## Scope

The first implementation deliberately establishes a trustworthy block-history core. Container inventories are already part of the block transaction format. Entity inventories, item entities, chat/command attribution, advanced block-entity NBT, preview jobs, selectors and external database backends are separate capabilities and will be added behind explicit domain abstractions rather than by expanding one monolithic listener.
