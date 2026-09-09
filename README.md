# Pixel-Protect

Pixel-Protect is a standalone forensic logging and anti-griefing plugin for **Paper 26.2**. The current implementation is intentionally focused: it records the information needed to reconstruct **who moved what, from where, to where, when, and through which transport system**, while also retaining block-change history for safe recovery.

The project is independent of PixelRPG and does not depend on CoreProtect. It uses modern Paper APIs, Mojang mappings and Java 25.

## Current status

- **Platform:** Paper 26.2
- **Paper Dev Bundle:** `26.2.build.121-stable`
- **Java:** 25
- **Mappings:** Mojang mappings through Paperweight
- **Plugin descriptor:** `paper-plugin.yml`
- **Storage:** local JSONL by default; optional MySQL/MariaDB backend
- **Build:** CI green
- **Legacy CraftBukkit / versioned NMS:** not used

## What Pixel-Protect currently does

Pixel-Protect is built around one forensic model:

**Actor → Transaction → Source → Item → Amount → Destination → Attribution → Chain → Restore**

The important result is that a log entry is not just “a chest changed”. It can describe the actual transfer:

```text
Hazel hat 32 diamond vom Inventar in Kiste gelegt.
Quelle: Spielerinventar
Ziel: Kiste bei world 125 / 64 / -32
Zeit: 09.09.2026 14:32:18
```

For automated transport the attribution follows the initiating transport endpoint where ownership is known:

```text
32 diamond wurden automatisch von Kiste A nach Kiste B transportiert.
Transportsystem platziert von: Hazel
```

This is the core forensic purpose of the plugin: **not only what changed, but who caused or owned the transfer path.**

## Logged transfer types

The current focused implementation covers:

- Player inventory → container
- Container → player inventory
- Container → container automation
- Hopper / transport-chain movement through `InventoryMoveItemEvent`
- Item pickup from the ground into a container
- Player dropping items to the ground
- Player picking items up from the ground
- Inventory changes caused by normal player interaction, including stack movement and splitting/merging where the before/after state can be determined
- Inventory minecarts and other supported inventory-holding entities

For automated transfers, Pixel-Protect records the initiating endpoint and resolves its recorded placer/owner where possible. If no owner can be established, the attribution is explicitly reported as unknown rather than guessed.

## Block and ownership logging

The world audit records:

- block placement
- block breaking
- inventory-holding entity placement
- exact world and XYZ coordinates
- block type
- block state/data before and after the change
- player attribution
- transaction identity

Container blocks and supported inventory entities are associated with their placer so later automated transfers can be attributed to the person who created the transport system.

## Inspector

Enable inspector mode with:

```text
/pp inspector
```

`/pp inspect` is also available.

While inspector mode is active, left- or right-clicking a block performs an **asynchronous forensic lookup** instead of opening or modifying the block.

The lookup combines block history and transfer history around the clicked position. Results include, where available:

- who performed the action
- item and exact amount
- source endpoint
- destination endpoint
- world and coordinates
- timestamp using the server/JVM timezone
- transport-system placer/owner
- transaction ID
- chain ID

Every result is separated by:

```text
________________________________________________________________________________
```

This keeps multiple forensic events visually distinct in chat.

## Lookup

The current command set is intentionally small:

```text
/pp inspector
/pp inspect
/pp lookup [radius] [time]
/pp rollback <radius> <time>
/pp status
```

Supported duration formats include:

```text
30s
30m
2h
1d
1w
```

Lookup and database/file processing run asynchronously so the main server thread is not blocked by storage access.

## Rollback

Rollback selection is performed asynchronously. The actual world mutations are scheduled back onto the appropriate Paper thread.

The rollback engine processes matching block and transfer records in **one chronological reverse order**. This is important for cases such as a container being broken after items were moved: the inventory transition is reversed before the container state is removed/restored in the wrong phase.

Transfer rollback is conservative:

- it identifies the exact recorded item data
- removes the recorded amount from the destination
- restores it to the source
- detects insufficient space or quantity instead of silently destroying unrelated items
- records `ACTIVE`, `ROLLED_BACK`, `CONFLICT` or `ERROR` state

Block rollback is also conflict-safe. A block is only reverted when its current live state still matches the recorded post-change state. Newer changes are therefore not blindly overwritten.

Player inventory rollback requires the player inventory to be available; the implementation does not modify offline player-data files through unsafe server internals.

## Storage

### Local storage — default

The default backend is **local JSONL**. It is append-oriented and avoids rewriting a complete history file for every event.

The plugin creates:

```text
plugins/Pixel-Protect/logs/
├── transfer_logs.jsonl
├── block_logs.jsonl
└── endpoint_owners.jsonl
```

JSONL was chosen because forensic history is naturally append-heavy: one JSON record per line can be written without loading and rewriting the entire history on every event.

### MySQL / MariaDB — optional

MySQL/MariaDB remains available as an optional backend. HikariCP provides the connection pool and MySQL Connector/J provides the driver.

The database schema contains the corresponding transfer, block and endpoint ownership records. Local storage can also be used as a fallback if the configured MySQL backend cannot be initialized.

## Asynchronous architecture

The event path is designed around a bounded in-memory queue:

1. A Paper event captures the relevant immutable state.
2. The record is submitted to the asynchronous logging queue.
3. A dedicated worker performs persistence work away from the main event thread.
4. Inspector and lookup queries execute asynchronously.
5. Rollback selection executes asynchronously.
6. Only the actual Minecraft world/inventory mutations return to Paper scheduling APIs.

The default queue capacity is `100000` entries.

The architecture deliberately separates **forensic data collection** from **world mutation** so database/file operations do not become a source of normal gameplay lag.

## Configuration

The current default configuration is:

```yaml
storage:
  mode: local
  fallback-to-local: true

mysql:
  host: 127.0.0.1
  port: 3306
  database: pixelprotect
  username: pixelprotect
  password: change-me
  pool-size: 8

logging:
  queue-capacity: 100000
```

`storage.mode: local` is the recommended current default. Set it to `mysql` when MySQL/MariaDB should be the primary backend.

## Commands and permission

The plugin command root is `/pp`.

```text
/pp inspector                 Toggle inspector mode
/pp inspect                   Inspector alias
/pp lookup [radius] [time]    Forensic history lookup
/pp rollback <radius> <time>  Asynchronous rollback
/pp status                    Runtime/storage status
```

Administrative access uses:

```text
pixelprotect.admin
```

## Build

Requirements:

- Java 25
- Gradle
- Paperweight UserDev

Build with:

```text
gradle clean build --no-daemon
```

The build uses:

- `io.papermc.paperweight.userdev` `2.0.0-beta.21`
- Shadow `9.6.1`
- Gson `2.13.1`
- HikariCP `7.0.2`
- MySQL Connector/J `9.7.0`

The shaded plugin artifact is produced under:

```text
build/libs/PixelProtect.jar
```

## Project structure

```text
src/main/java/de/pixelprotect/
├── PixelProtect.java
├── command/
│   └── PixelProtectCommand.java
├── database/
│   └── DatabaseManager.java
├── listener/
│   └── ForensicListener.java
├── model/
│   ├── BlockLog.java
│   ├── Endpoint.java
│   ├── EndpointType.java
│   ├── Owner.java
│   └── TransferLog.java
├── service/
│   ├── AsyncLogQueue.java
│   ├── InspectorService.java
│   ├── OwnershipService.java
│   ├── RollbackService.java
│   ├── TransferService.java
│   └── WorldAuditService.java
└── util/
    ├── EndpointResolver.java
    ├── InventoryDiff.java
    └── ItemCodec.java

src/main/resources/
├── config.yml
└── paper-plugin.yml
```

## Design boundaries

Pixel-Protect intentionally does **not** use CraftBukkit classes, versioned NMS packages or legacy `plugin.yml` registration.

It also does not pretend to know an actor when the available event data cannot establish one. Automated attribution is based on recorded endpoint ownership; otherwise the result is marked as unknown.

The current implementation is deliberately focused on the forensic transfer/rollback problem rather than reproducing every historical CoreProtect feature. The source of truth is the recorded transaction history plus the current live world state used for conflict checks.
