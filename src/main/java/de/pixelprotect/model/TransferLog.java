package de.pixelprotect.model;

import java.time.Instant;
import java.util.UUID;

public record TransferLog(
        Instant timestamp,
        UUID transactionId,
        UUID chainId,
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
