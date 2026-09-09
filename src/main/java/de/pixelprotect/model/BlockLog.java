package de.pixelprotect.model;

import java.time.Instant;
import java.util.UUID;

public record BlockLog(
        UUID transactionId,
        Instant timestamp,
        UUID playerUuid,
        String playerName,
        String world,
        int x,
        int y,
        int z,
        String beforeData,
        String afterData,
        String action,
        String blockType
) {}
