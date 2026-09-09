package de.pixelprotect.model;

import java.time.Instant;
import java.util.UUID;

public record TransferLog(
        UUID transactionId,
        Instant timestamp,
        UUID actorUuid,
        String actorName,
        UUID attributionUuid,
        String attributionName,
        Endpoint source,
        Endpoint destination,
        String itemKey,
        String itemData,
        int amount,
        String action
) {}
