# Pixel-Protect — Forensic Code Audit

**Audit target:** `HazelTheSquirrel/Pixel-Protect` (`main`)

**Audit date:** 2026-09-09

**Scope:** static forensic review of the current repository state, with emphasis on correctness of evidence capture, attribution, transaction grouping, persistence, rollback integrity, threading, Paper API usage, shutdown behavior, and operational safety.

**Method:** repository structure, current Java source, Gradle build, `paper-plugin.yml`, configuration, and recent commit history were inspected. This is a source-level audit; no live Paper 26.2 server/database execution was available during this audit. Findings marked **CONFIRMED** are directly established by the current source. Findings marked **HIGH-LIKELIHOOD** describe behavior that follows from the implementation and should be runtime-tested before release.

## Severity

- **CRITICAL** — forensic evidence can be lost, corrupted, or a rollback can produce an unsafe result.
- **HIGH** — core forensic attribution/logging/rollback can be materially wrong or incomplete.
- **MEDIUM** — important correctness, compatibility, operational, or maintainability defect.
- **LOW** — non-critical defect, missing hardening, or quality issue.

---

## Executive finding

The current implementation is **not production-ready as a forensic system**. The basic architecture is present, but the most important invariant — **every real inventory mutation must be captured exactly once, with the exact source, destination, actor, amount, item state, and a safely reversible transaction** — is not guaranteed.

The most serious defects are:

1. The inventory snapshot/diff mechanism is race-prone and can duplicate, merge, or miss transactions when multiple inventory events occur before the scheduled one-tick comparison runs.
2. The asynchronous logging queue can lose queued evidence during shutdown.
3. `TransferService` explicitly swallows queue-full failures, contradicting the queue's stated loss-prevention behavior.
4. Ownership persistence is performed through an independent executor instead of the dedicated logging queue, creating ordering and shutdown races between ownership records and transfer attribution.
5. The current rollback implementation mutates live inventories using aggregate item removal/addition rather than restoring the exact inventory state recorded by the forensic event. This can remove the wrong matching stacks and can fail to reconstruct the original slot arrangement/NBT state safely.
6. The five-character rollback ID is a truncated UUID prefix and is therefore not globally unique. The current lookup treats collisions as an error, which is safer than silently choosing one, but it means the displayed ID is not a reliable unique identifier.
7. Automated transport logging does not establish a complete causal chain across a multi-hop hopper/minecart system; each movement is treated as its own transaction and the chain UUID is generated but not used to reconstruct a complete forensic chain in the inspector or rollback workflow.
8. The player inventory capture is based on totals by item key rather than slot-level mutation information, which loses information required for exact rollback and can misattribute simultaneous changes involving identical item keys.
9. The plugin currently has no automated test suite for the critical forensic invariants.

---

# Findings

## PP-001 — CRITICAL — Inventory event capture is race-prone

**Status:** CONFIRMED

`ForensicListener` invokes `TransferService.capturePlayerInventoryChange()` for both `InventoryClickEvent` and `InventoryDragEvent` at `MONITOR`. The service immediately snapshots the inventories, then schedules the comparison with `Bukkit.getScheduler().runTask(plugin, ...)` for a later tick. fileciteturn128file0L2-L2 fileciteturn124file0L2-L2

### Failure mode

If a player performs multiple inventory actions in rapid succession, several events can enqueue snapshots whose `after` state is read later. Multiple events may therefore observe the same later inventory state. This can result in:

- duplicate transfer records;
- missing intermediate transfers;
- incorrect amounts;
- a single event absorbing mutations from a later event;
- incorrect transaction boundaries;
- rollback records that do not correspond to one real player action.

This is especially dangerous because the forensic UI groups records by `transactionId`, while the transaction boundary itself is established before the delayed comparison. fileciteturn124file0L2-L2

### Required correction

Capture the event's actual mutation semantics before the event completes, or maintain a transaction-safe before/after state machine keyed by the inventory view and event lifecycle. Do not use an unconditional next-tick snapshot as the authoritative transaction boundary.

---

## PP-002 — HIGH — Player inventory diff is aggregate-only and cannot guarantee exact attribution

**Status:** CONFIRMED

`InventoryDiff` reduces an inventory to totals keyed by `ItemCodec.key(stack)` and keeps only one representative stack per key. fileciteturn129file0L2-L2

### Failure mode

Slot positions, stack positions, cursor state, hotbar/off-hand distinctions, and the exact source stack are discarded. For a forensic logger this is insufficient when several identical item keys exist in different slots or when one transaction moves/splits/merges stacks.

The rollback engine subsequently operates on aggregate inventory contents rather than an exact pre-event inventory snapshot. This breaks the strongest forensic guarantee: **rollback must reverse the exact recorded mutation, not merely attempt to restore equivalent item counts**.

### Required correction

Record a slot-level mutation or immutable before/after inventory state for every logical transaction. The forensic transaction should contain enough information to deterministically reverse the operation without guessing which matching stack to modify.

---

## PP-003 — HIGH — `InventoryClickEvent` and `InventoryDragEvent` can produce overlapping transaction records

**Status:** CONFIRMED

Both event handlers independently call the same delayed snapshot mechanism. fileciteturn128file0L2-L2

### Failure mode

A drag/click sequence can cause multiple capture requests against the same inventory view. There is no per-view lock, event sequence number, or pending-capture coalescing. Therefore there is no invariant guaranteeing **one logical inventory action = one transaction ID**.

This directly conflicts with the intended inspector UX where one logical action must be represented by one message and one rollback ID.

### Required correction

Introduce an explicit transaction capture coordinator that creates exactly one transaction object per completed Bukkit inventory interaction.

---

## PP-004 — CRITICAL — Logging queue can lose evidence during plugin shutdown

**Status:** CONFIRMED

`AsyncLogQueue.close()` sets `running=false`, interrupts the worker, and then waits. The worker exits immediately when `InterruptedException` is received, even though the loop condition is designed to continue while the queue is non-empty. fileciteturn136file0L2-L2

### Failure mode

Queued records that have not yet reached the database can remain in memory and be discarded during shutdown/reload.

This is a direct forensic evidence-loss path.

### Required correction

Shutdown must stop accepting new records, drain the queue completely, flush all records successfully, then close the database. Interrupt should not discard pending records. A bounded shutdown timeout should produce an explicit error if a flush cannot complete.

---

## PP-005 — CRITICAL — Queue overflow is silently swallowed by `TransferService`

**Status:** CONFIRMED

`AsyncLogQueue.offer()` intentionally throws when the bounded queue is full and explicitly states that it refuses to silently lose forensic data. However `TransferService.submitTransfer()` catches `RuntimeException` and ignores it. fileciteturn136file0L2-L2 fileciteturn124file0L2-L2

### Failure mode

When the queue reaches capacity, the logger drops the record without exposing the failure to the caller or guaranteeing persistence.

The queue therefore claims loss protection while the transfer layer defeats it.

### Required correction

Never swallow queue submission failure. Apply an explicit backpressure/failure policy: block the producer only where safe, use a secondary durable spool, or reject/log the event as an explicit forensic-storage failure. For a security logger, silent loss is unacceptable.

---

## PP-006 — HIGH — Ownership writes bypass the dedicated forensic queue

**Status:** CONFIRMED

`OwnershipService.record()` writes ownership data using a separate `CompletableFuture.runAsync(...)`, while normal forensic records use `AsyncLogQueue`. fileciteturn135file0L2-L2

### Failure mode

There is no ordering guarantee between:

1. recording who placed a container/transport endpoint;
2. the subsequent automated transfer that resolves that owner;
3. plugin shutdown.

An automated transfer can therefore execute while the owner persistence task has not completed. During shutdown, the ownership task can also outlive the plugin lifecycle.

### Required correction

Use a single ordered persistence mechanism or explicitly coordinate ownership persistence and lookup. In-memory ownership should be authoritative immediately, while durable ownership writes must be flushed during controlled shutdown.

---

## PP-007 — HIGH — Automated attribution is not guaranteed to be available at transfer time

**Status:** CONFIRMED

Automated transfers resolve attribution asynchronously using `OwnershipService.resolve()`. If lookup fails, `TransferService` converts the exception to `Optional.empty()`. fileciteturn124file0L2-L2 fileciteturn135file0L2-L2

### Failure mode

A failed owner lookup becomes an unattributed transfer instead of an explicit storage/attribution failure. This undermines the requirement to know who placed the transport system.

### Required correction

Distinguish `OWNER_UNKNOWN` from `OWNER_LOOKUP_FAILED`, persist the failure state, and maintain enough endpoint history to recover attribution later.

---

## PP-008 — HIGH — Automated chain IDs are generated but not actually used as a forensic chain

**Status:** CONFIRMED

Each automated movement receives fresh `UUID.randomUUID()` transaction and chain IDs. The schema indexes `chain_id`, but the current inspector groups player transfers by `transactionId`, and rollback operates on the resolved rollback ID/transaction rather than traversing a complete chain. fileciteturn124file0L2-L2 fileciteturn125file0L2-L2

### Failure mode

For a hopper chain such as:

`Player -> Chest -> Hopper -> Hopper -> Chest -> Minecart`

the system does not yet provide a deterministic user-facing causal chain showing the entire movement as one forensic story.

### Required correction

Persist and expose a true causal chain model: initiating actor, origin endpoint, every hop, final destination, timestamps, quantities, and the endpoint owner responsible for the transport system.

---

## PP-009 — HIGH — Five-character rollback IDs are not unique

**Status:** CONFIRMED

The displayed rollback ID is generated from only the first five hexadecimal characters of the transaction UUID. fileciteturn144file0L2-L2

### Failure mode

Five hexadecimal characters provide only 16^5 = 1,048,576 possible IDs. At sufficient transaction volume, collisions are inevitable. The current rollback service detects multiple transaction IDs and refuses the rollback, which prevents an incorrect rollback but does not make the displayed ID unique. fileciteturn125file0L2-L2

### Required correction

Use a persistent unique rollback identifier. If the short UI ID is retained, maintain a collision-safe mapping table and regenerate/extend the display identifier when a collision occurs.

---

## PP-010 — HIGH — Rollback is not an exact inverse of the recorded inventory mutation

**Status:** CONFIRMED

`RollbackService.reverse()` removes an item from the destination using `removeItemAnySlot()` and then adds the recorded amount to the source. fileciteturn125file0L2-L2

### Failure mode

This is count-based compensation, not exact state restoration. It can:

- remove a different stack containing an equivalent item;
- change stack distribution;
- fail to restore the original slot;
- interact incorrectly with concurrent player inventory changes;
- leave a player with a semantically different inventory despite equal item counts;
- fail when the source inventory has insufficient capacity.

The fallback path can also mutate the destination again after a failed source restore, making the rollback behavior more complex and less deterministic.

### Required correction

Rollback must operate on a recorded, versioned before/after state or slot-level delta. It must verify the expected current state, apply the inverse atomically on the main thread, and mark a conflict without destructive guessing if the state has changed.

---

## PP-011 — HIGH — Rollback can conflict with concurrent inventory activity

**Status:** CONFIRMED

The database lookup occurs asynchronously and the actual inventory mutation occurs later on the main thread. There is no transaction version, lock, or expected-state fingerprint stored for inventory endpoints. fileciteturn125file0L2-L2

### Failure mode

A player can legitimately change the inventory between the original event and rollback. The rollback then operates against a newer state without a robust compare-and-swap invariant.

### Required correction

Store endpoint state hashes/version numbers and require the live state to match the expected rollback precondition. Otherwise mark the record `CONFLICT` without modifying inventory.

---

## PP-012 — HIGH — Hopper pickup is logged without verifying the final destination state

**Status:** HIGH-LIKELIHOOD

`captureHopperPickup()` immediately submits a transfer after the event, without taking a destination before/after snapshot analogous to `captureAutomation()`. fileciteturn124file0L2-L2

### Failure mode

The event represents an attempted item pickup. The implementation records the full item amount without independently proving that exactly that amount entered the destination inventory.

### Required correction

Record the actual post-event destination delta and the ground-item state. Only persist the amount that demonstrably arrived.

---

## PP-013 — HIGH — Player pickup logging does not verify the actual inserted amount

**Status:** HIGH-LIKELIHOOD

`capturePlayerPickup()` records the entire dropped stack amount directly from the item entity and does not compare the player's inventory before/after. fileciteturn124file0L2-L2

### Failure mode

Inventory capacity, partial pickup, item merging, event ordering, or other plugins can make the actual inserted amount differ from the entity stack amount.

### Required correction

Capture the exact player inventory delta and the remaining ground item amount after the event.

---

## PP-014 — HIGH — Block break logging obtains the after-state asynchronously without an immutable event snapshot

**Status:** CONFIRMED

`WorldAuditService.blockBreak()` records the pre-break state immediately but schedules the construction of the `BlockLog` for the next task, where `block.getBlockData()` is read again. fileciteturn137file0L2-L2

### Failure mode

The block's state can have changed again before the scheduled task executes. The recorded `after_data` may therefore describe a later state rather than the direct result of the player's break.

### Required correction

Capture the complete before/after state at a deterministic event boundary. For a block break, the expected after-state should be explicitly defined and validated rather than inferred from a later world read.

---

## PP-015 — HIGH — Block rollback only succeeds when the current block exactly equals the recorded after-state, but no event-level concurrency protection exists

**Status:** CONFIRMED

`reverseBlock()` compares the live block data to the recorded `afterData()` before applying the recorded `beforeData()`. fileciteturn125file0L2-L2

### Assessment

The comparison is a good safety mechanism, but it is incomplete as a forensic rollback system because the block log does not record all relevant world-side dependencies such as inventories/entities that may have been affected by the change. A block rollback can therefore restore the block state while leaving associated container/entity data inconsistent.

### Required correction

Define rollback semantics per block type and include dependent state where required, especially container blocks and placed inventory entities.

---

## PP-016 — HIGH — Container placement ownership is not removed when the container is broken

**Status:** CONFIRMED

`WorldAuditService` records ownership when an inventory block is placed, but the block-break path shown does not remove or invalidate the corresponding `endpoint_owners` record. fileciteturn137file0L2-L2

### Failure mode

A block can be destroyed and a different container later placed at the same coordinates. Depending on the endpoint identity and cache state, old ownership can remain associated with the endpoint.

This is particularly dangerous for hopper attribution because ownership is security evidence.

### Required correction

Invalidate ownership on destructive endpoint removal and version ownership by endpoint incarnation/placement transaction.

---

## PP-017 — HIGH — Ownership is coordinate/entity based without an incarnation identity

**Status:** CONFIRMED

Endpoint ownership is persisted under `endpoint.identity()` and the database uses `endpoint_id` as the primary key. Ownership updates overwrite the previous owner. fileciteturn135file0L2-L2 fileciteturn126file0L2-L2

### Failure mode

A coordinate can represent multiple different physical container incarnations over time. A simple endpoint ID cannot by itself distinguish the original chest from a replacement chest placed later at the same position.

### Required correction

Use an endpoint incarnation ID tied to the placement transaction and maintain ownership history rather than only the current owner.

---

## PP-018 — MEDIUM — Local JSONL storage is not a transactional database

**Status:** CONFIRMED

The default configuration selects local JSONL storage. fileciteturn140file0L2-L2

### Failure mode

JSONL append/rewrite storage does not provide database-level transactions, indexes, crash-consistent multi-record commits, or efficient large-scale forensic querying. Local inspector queries require scanning the files in memory in the current implementation path.

### Required correction

Treat local JSONL as a durable spool/debug backend, not the long-term forensic database for production-scale servers. If local mode remains supported, add rotation, corruption recovery, indexing, checksums, and explicit durability guarantees.

---

## PP-019 — HIGH — Local storage corruption handling silently skips malformed records

**Status:** CONFIRMED

The local initialization and rollback lookup paths contain broad parsing catches that ignore malformed JSON records rather than surfacing data corruption. The current database implementation also ignores malformed ID records while determining maximum IDs. fileciteturn126file0L2-L2

### Failure mode

A damaged forensic record can disappear from searches without an operator being told that evidence is missing.

### Required correction

Record corruption explicitly, preserve the raw line, report the file and line number, and expose a storage-integrity warning. Never silently convert corrupted evidence into an apparently clean audit result.

---

## PP-020 — HIGH — MySQL and local backends have materially different operational characteristics

**Status:** CONFIRMED

The configuration allows either local or MySQL as the primary store, with automatic fallback to local on MySQL initialization failure. fileciteturn122file0L2-L2 fileciteturn140file0L2-L2

### Failure mode

A server can start successfully after a MySQL failure but silently switch to a different persistence backend. Historical evidence may then be split across stores and operators may believe the MySQL forensic database is authoritative when it is not.

### Required correction

Make backend failover explicit in server logs/status and preferably fail closed for forensic operation unless the operator explicitly permits degraded local mode.

---

## PP-021 — HIGH — Database schema has no explicit migration/version mechanism

**Status:** CONFIRMED

The database initialization consists of `CREATE TABLE IF NOT EXISTS` statements. There is no schema version table or migration framework in the current implementation. fileciteturn126file0L2-L2

### Failure mode

Future schema changes cannot be safely applied to an existing installation merely by changing the `CREATE TABLE` statement. Existing databases can therefore run with an obsolete schema.

### Required correction

Add a schema version table and ordered migrations. Startup must validate the actual schema version before enabling forensic logging.

---

## PP-022 — MEDIUM — Database queries use bounding boxes rather than true radius geometry

**Status:** CONFIRMED

The inspector calls `findTransfers()`/`findBlocks()` with a radius, but the SQL queries use `BETWEEN` ranges on X/Y/Z. fileciteturn126file0L2-L2

### Failure mode

The resulting search volume is a cube, not a sphere. A block at a corner of the cube can be farther than the requested radius.

### Required correction

Apply a precise squared-distance filter after the indexed bounding-box query, or express the appropriate distance condition in SQL.

---

## PP-023 — MEDIUM — Inspector searches a fixed 10-year window instead of a defined forensic retention policy

**Status:** CONFIRMED

The inspector hard-codes `Instant.now().minus(Duration.ofDays(3650))`. fileciteturn144file0L2-L2

### Failure mode

Retention behavior is not configurable, and leap years make a fixed day count an imprecise representation of ten calendar years.

### Required correction

Make retention configurable and use an explicit `Instant`/calendar policy. The database should own the retention/query boundary.

---

## PP-024 — MEDIUM — Inspector result limit is fixed and can hide relevant evidence

**Status:** CONFIRMED

The inspector asks for a maximum of 50 transfer and 50 block results. fileciteturn144file0L2-L2

### Failure mode

A heavily used container can have more than 50 relevant records. The UI does not state that results are truncated.

### Required correction

Return a deterministic paginated result or explicitly display that the result set was truncated, with commands for older/newer pages.

---

## PP-025 — HIGH — Inspector groups only by transaction ID, not by complete logical event semantics

**Status:** CONFIRMED

The current inspector groups transfer rows by `transactionId`, then sends one message per group. fileciteturn144file0L2-L2

### Failure mode

The grouping is only as correct as the transaction IDs created upstream. Because player inventory capture is race-prone, the UI can faithfully display an incorrect grouping.

The one-message UI therefore cannot compensate for an incorrect transaction model.

### Required correction

Fix transaction capture first, then make the persisted transaction itself the authoritative logical-event boundary.

---

## PP-026 — MEDIUM — Automated transfer UI does not provide the complete requested chain story

**Status:** CONFIRMED

The automated format reports one source, one destination, and optionally the transport-system owner. fileciteturn144file0L2-L2

### Failure mode

For multi-hop systems, the user cannot reconstruct the full path from one inspector message. The underlying chain ID is not presented or traversed.

### Required correction

Add a chain-aware forensic query that shows the complete movement path while still keeping the normal single-action display concise.

---

## PP-027 — HIGH — Entity placement is only used to establish ownership; the entity placement itself is not logged as a block/world forensic event

**Status:** CONFIRMED

`entityPlace()` records ownership for inventory-holding entities but does not create a `BlockLog`/entity-placement audit record. fileciteturn137file0L2-L2

### Failure mode

There is no complete forensic record showing who placed the minecart/container entity, where, and when, despite later transfers being attributed to that owner.

### Required correction

Persist entity placement/removal as first-class world audit events with entity UUID, type, location, actor, and transaction ID.

---

## PP-028 — HIGH — Entity ownership can become stale after entity removal/despawn

**Status:** HIGH-LIKELIHOOD

The current ownership service stores an entity endpoint owner, but the shown listener does not include an entity-removal/despawn invalidation path. fileciteturn135file0L2-L2 fileciteturn137file0L2-L2

### Failure mode

A removed minecart UUID should never be treated as the same forensic endpoint as a later entity. Stale ownership can produce false attribution if endpoint resolution or persistence is reused incorrectly.

### Required correction

Track entity lifecycle and use an immutable entity incarnation UUID plus removal timestamp.

---

## PP-029 — MEDIUM — Block/container ownership and transfer logging are not transactionally linked

**Status:** CONFIRMED

Block placement and transfer records use separate transaction IDs, and ownership persistence occurs independently. fileciteturn137file0L2-L2

### Failure mode

A forensic investigator cannot reliably traverse from a transfer to the exact placement event that established the endpoint's owner.

### Required correction

Persist `owner_source_transaction_id`/endpoint incarnation metadata on the transfer record.

---

## PP-030 — HIGH — Rollback of transfers does not restore ground-item/entity state

**Status:** CONFIRMED

`RollbackService.inventory()` only resolves PLAYER, BLOCK_CONTAINER, and MINECART inventories. Ground endpoints cannot be restored by `reverse()`. fileciteturn125file0L2-L2

### Failure mode

Transfers involving `GROUND` are logged, but the rollback engine cannot reverse them because the ground endpoint is not an inventory. This means some logged transactions are not actually rollback-capable.

### Required correction

Either make ground transfers explicitly non-rollbackable with a clear state, or implement safe item-entity restoration using a persisted item snapshot and lifecycle-aware entity handling.

---

## PP-031 — HIGH — Player rollback requires the player to be online

**Status:** CONFIRMED

The PLAYER endpoint rollback path uses `Bukkit.getPlayer(endpoint.playerId())` and returns `null` when the player is offline. fileciteturn125file0L2-L2

### Failure mode

A historical rollback cannot restore an offline player's inventory. This can leave a transaction partially rolled back.

### Required correction

Define an offline-player rollback strategy based on authoritative player inventory persistence, or explicitly reject the whole transaction before mutating any other endpoint.

---

## PP-032 — CRITICAL — Rollback is not atomic across a multi-record transaction

**Status:** CONFIRMED

`RollbackService.apply()` iterates over transfer/block events and independently marks each row `ROLLED_BACK`, `CONFLICT`, or `ERROR`. fileciteturn125file0L2-L2

### Failure mode

One part of a logical transaction can succeed while another part fails. The result is a partially reversed transaction.

For a multi-item inventory action this can leave some items restored and others untouched.

### Required correction

Use a transaction-level rollback coordinator with preflight validation of every affected endpoint, then apply the whole inverse operation or reject the transaction before making changes. Persist a transaction-level rollback state.

---

## PP-033 — HIGH — Rollback state updates are not transactionally coupled to world/inventory mutations

**Status:** CONFIRMED

The rollback implementation changes live state and then separately updates the database row's rollback state. fileciteturn125file0L2-L2

### Failure mode

A server crash between the world mutation and the database state update can produce a state where the world is rolled back but the database still says `ACTIVE`, or vice versa.

### Required correction

Use a durable rollback journal/state machine: `PREPARING -> APPLYING -> APPLIED` with recovery logic on startup.

---

## PP-034 — MEDIUM — Rollback event ordering is not sufficient to guarantee causal reversal

**Status:** CONFIRMED

Events are sorted by timestamp descending before reversal. fileciteturn125file0L2-L2

### Failure mode

Timestamp order alone is insufficient when multiple operations occur within the same clock resolution or when asynchronous logging order differs from game-event causal order.

### Required correction

Persist a monotonic per-server event sequence in addition to wall-clock time and use it for deterministic rollback ordering.

---

## PP-035 — MEDIUM — `Instant.now()` is used after asynchronous scheduling, so logged timestamps can represent persistence/capture delay

**Status:** CONFIRMED

Several records call `Instant.now()` when the asynchronous operation executes rather than using an immutable event timestamp captured at the exact event boundary. fileciteturn124file0L2-L2 fileciteturn137file0L2-L2

### Failure mode

Forensic timestamps can drift from the actual player action, especially under load.

### Required correction

Capture event timestamp immediately at the event boundary and carry it through the asynchronous pipeline.

---

## PP-036 — MEDIUM — The plugin can disable itself after initialization failure without exposing a structured degraded-state reason to operators

**Status:** CONFIRMED

`onEnable()` catches the startup exception, logs a message, closes the database, and disables the plugin. fileciteturn122file0L2-L2

### Assessment

Self-disable is safer than running without storage, but the current status command is static and does not expose actual backend/queue/degraded state. fileciteturn127file0L2-L2

### Required correction

Implement a real health state: storage backend, queue depth, last write error, ownership subsystem state, and last successful flush.

---

## PP-037 — LOW — `/pp status` reports success without checking actual subsystem health

**Status:** CONFIRMED

The command prints a fixed success message rather than querying database/queue state. fileciteturn127file0L2-L2

### Required correction

Make status authoritative and include degraded/failure states.

---

## PP-038 — MEDIUM — Command permission is not explicitly represented in the visible `paper-plugin.yml`

**Status:** CONFIRMED

The command implementation returns `pixelprotect.admin` from `BasicCommand.permission()`, but the resource file contains only plugin metadata. fileciteturn127file0L2-L2 fileciteturn132file0L2-L2

### Assessment

Depending on Paper's command registration/permission behavior, this may be sufficient at runtime, but it should be verified against the exact Paper 26.2 command API rather than assumed.

### Required correction

Add an integration test on the target Paper build verifying command registration, suggestions, permission enforcement, and console behavior.

---

## PP-039 — LOW — Console cannot use rollback/inspector commands

**Status:** CONFIRMED

The command immediately rejects any non-player sender. fileciteturn127file0L2-L2

### Assessment

This is not inherently unsafe, but it limits operational recovery. A production administration tool normally needs console-compatible rollback and status operations.

### Required correction

Keep inspector player-only, but make rollback/status usable from console where world/player context is not required.

---

## PP-040 — MEDIUM — No audit protection against rollback-induced logging loops is visible

**Status:** HIGH-LIKELIHOOD

Rollback mutates inventories and blocks through Bukkit APIs. The listener is active during rollback, and no explicit rollback context/suppression mechanism is visible in the current architecture. fileciteturn128file0L2-L2 fileciteturn125file0L2-L2

### Failure mode

A rollback can generate new forensic events while it is reversing old events. This can create recursive/noisy audit records and potentially make subsequent rollback operations confusing.

### Required correction

Use a scoped rollback context keyed by transaction/operation and explicitly mark rollback-generated mutations as system operations that are not treated as ordinary player actions.

---

## PP-041 — HIGH — Container rollback can alter inventory state while a player is actively viewing the inventory

**Status:** HIGH-LIKELIHOOD

`reverse()` directly mutates inventories on the main thread but does not close or lock an affected open inventory view. fileciteturn125file0L2-L2

### Failure mode

A player can interact with the same container concurrently with rollback, producing race-like semantic conflicts even though Bukkit mutations are executed on the main thread.

### Required correction

Preflight affected viewers, temporarily lock/close affected inventories, apply rollback, then reopen safely if appropriate.

---

## PP-042 — HIGH — Block/container rollback does not restore block inventories or block entity data

**Status:** CONFIRMED

`reverseBlock()` restores only `BlockData`. fileciteturn125file0L2-L2

### Failure mode

Restoring a chest/furnace/hopper-like block's block data does not restore its block entity inventory, custom name, contents, or other persistent state.

### Required correction

For block entities, record and restore the complete relevant block-entity state in addition to block data, or explicitly reject unsafe block rollback.

---

## PP-043 — HIGH — The current forensic model cannot guarantee exact item component/NBT restoration after aggregate rollback

**Status:** CONFIRMED

`ItemCodec` preserves serialized item data, but `reverse()` decodes one representative item and uses it for aggregate add/remove operations. fileciteturn125file0L2-L2

### Failure mode

Even if the stored item snapshot contains exact components, aggregate inventory manipulation does not guarantee restoration of the original stack/component distribution.

### Required correction

Use the serialized exact stack(s) at their recorded slots as rollback state.

---

## PP-044 — MEDIUM — Broad exception handling makes forensic failures difficult to diagnose

**Status:** CONFIRMED

The code frequently catches broad `Exception`/`RuntimeException` values and either reduces them to a generic message or ignores them. Examples include queue submission and ownership resolution. fileciteturn124file0L2-L2 fileciteturn135file0L2-L2

### Failure mode

The system can degrade from "precise forensic logging" to "unknown/missing evidence" without sufficient diagnostics.

### Required correction

Classify failures, include transaction/event IDs, log stack traces for storage failures, and persist explicit failure states where forensic completeness is affected.

---

## PP-045 — MEDIUM — No integrity checksum/hash is stored for forensic records

**Status:** CONFIRMED

The current transfer/block records store the payload but do not show a cryptographic integrity hash or chained journal hash. fileciteturn126file0L2-L2

### Failure mode

A forensic log can be modified at rest without the plugin being able to demonstrate that it was altered.

### Required correction

Add per-record hashes and optionally a hash chain/signature strategy for tamper evidence.

---

## PP-046 — MEDIUM — No durable monotonic sequence number is exposed for cross-table event ordering

**Status:** CONFIRMED

Transfer and block tables each use independent auto-increment IDs, while their logical transaction IDs are UUIDs. fileciteturn126file0L2-L2

### Failure mode

Cross-type event ordering cannot be reconstructed with guaranteed total ordering from the current identifiers alone.

### Required correction

Add a shared server-side sequence/event journal ID or a causal ordering mechanism.

---

## PP-047 — MEDIUM — Storage initialization can fall back from MySQL to local without migrating or reconciling existing evidence

**Status:** CONFIRMED

The startup path closes the MySQL datasource and initializes local storage after a MySQL initialization failure. fileciteturn126file0L2-L2

### Failure mode

The server can continue with a fresh local evidence store while historical MySQL evidence remains elsewhere. There is no reconciliation/index telling the inspector that two stores exist.

### Required correction

Expose the backend transition, retain a backend provenance marker, and provide an explicit recovery/migration path.

---

## PP-048 — LOW — MySQL configuration is shipped with a placeholder password and no startup validation policy

**Status:** CONFIRMED

The default config uses `password: change-me`. fileciteturn140file0L2-L2

### Assessment

This is acceptable as a sample configuration, but production startup should detect the default credential when MySQL mode is selected and refuse to start unless explicitly overridden.

---

## PP-049 — MEDIUM — No retention/rotation/compaction strategy is implemented for local forensic files

**Status:** CONFIRMED

The local backend appends to `transfer_logs.jsonl`, `block_logs.jsonl`, and `endpoint_owners.jsonl`. The shown configuration has no rotation or retention controls. fileciteturn126file0L2-L2 fileciteturn140file0L2-L2

### Failure mode

A long-running server can grow the forensic files without bound, increasing query and startup costs.

### Required correction

Implement rotation, retention, archival, compression, and integrity verification.

---

## PP-050 — MEDIUM — No automated integration test validates the target Paper 26.2 runtime behavior

**Status:** CONFIRMED from repository scope inspected

No test source tree or test suite is present in the reviewed project structure. The repository currently consists of build configuration and the runtime plugin source/resources. fileciteturn141file0L2-L2

### Required correction

Add unit tests for diffing/serialization/rollback ID resolution and integration tests on Paper 26.2 for inventory events, hoppers, minecarts, block entities, commands, and rollback conflict handling.

---

# Requirement compliance matrix

| Requirement | Current state | Audit result |
|---|---|---|
| Paper 26.2 target | Configured | Needs runtime verification |
| Java 25 | Configured | Good |
| Modern `paper-plugin.yml` | Present | Good |
| Mojang mappings / no CraftBukkit imports | No legacy CraftBukkit imports visible in reviewed source | Good, but compile verification required |
| Gson 2.13.1 | Configured | Good |
| HikariCP 7.0.2 | Configured | Good |
| MySQL Connector/J 9.7.0 | Configured | Good |
| Async logging queue | Present | **Unsafe shutdown / overflow behavior** |
| Block place logging | Present | **Incomplete forensic lifecycle** |
| Block break logging | Present | **Delayed after-state issue** |
| Player inventory transfer logging | Present | **Race-prone / aggregate-only** |
| Hopper transfer logging | Present | **Insufficient post-event verification** |
| Minecart attribution | Partially present | **Lifecycle/chain incomplete** |
| Inspector mode | Present | **Depends on flawed transaction model** |
| One message per logical transaction | UI code attempts this | **Upstream event grouping not guaranteed** |
| Rollback by ID | Present | **ID collision + non-atomic rollback** |
| Exact inventory restoration | Not present | **Major defect** |
| Exact block-entity restoration | Not present | **Major defect** |
| Offline player rollback | Not supported | **Major limitation** |
| Durable shutdown flush | Not guaranteed | **Critical defect** |
| Schema migration | Not present | **Operational defect** |
| Automated tests | Not present | **Release blocker** |

---

# Release blockers

The following should be considered mandatory before calling Pixel-Protect production-ready:

1. Fix PP-001 / PP-003: replace delayed inventory snapshots with a deterministic event transaction model.
2. Fix PP-004 / PP-005: guarantee no silent queue loss and perform a complete shutdown drain.
3. Fix PP-010 / PP-011 / PP-032 / PP-033: redesign rollback around exact state, preflight validation, conflict detection, and transaction-level atomicity.
4. Fix PP-016 / PP-017 / PP-028: implement endpoint incarnation and ownership lifecycle history.
5. Fix PP-030 / PP-031 / PP-042: define and implement rollback semantics for ground items, offline players, and block entities.
6. Fix PP-009: replace the collision-prone five-hex-character ID with a persistent collision-safe mapping while retaining the short UI representation if desired.
7. Fix PP-045 / PP-046 if the system is intended to be used as genuine forensic evidence rather than merely an administrative logger.
8. Add the automated test suite in PP-050 and run it against the exact Paper 26.2 build configured by the project.

---

# Overall audit rating

**Current rating: NOT PRODUCTION READY**

The project has a valid architectural foundation and the current source is substantially cleaner than a typical prototype, but the system currently provides **best-effort logging**, not guaranteed forensic reconstruction and exact rollback.

The distinction is important: a normal logging plugin may tolerate an occasional missed event. A forensic anti-griefing system must not.

The target invariant should be:

> **One real game mutation → one immutable forensic event → exact actor/source/destination/amount/item state → durable persistence → deterministic inspector representation → conflict-safe exact inverse.**

The current implementation does not yet guarantee that invariant.
