# PixelProtect

A standalone, audit-first world history and rollback system for Paper 26.2+.

## Design goals

- Paper 26.x only; development target is Paper 26.2.
- Java 25 only.
- Paper plugin lifecycle and Brigadier commands.
- Folia-safe region scheduling for world mutation.
- SQLite persistence with asynchronous database work.
- Immutable audit records and guarded rollback operations.
- No dependency on PixelRPG or any other plugin.

This repository is being built as a real CoreProtect-style system: explicit domain models, deterministic persistence, bounded work, safety checks, and tests where practical. Features are implemented deliberately rather than copied from legacy APIs.

## Build

```text
./gradlew build
```

The resulting plugin is `build/libs/PixelProtect.jar`.
