package de.pixelprotect.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferLog(
        Instant timestamp,
        long sequence,
        UUID transactionId,
        UUID chainId,
        String rollbackId,
        UUID actorUuid,
        String actorName,
        UUID attributionUuid,
        String attributionName,
        Endpoint source,
        Endpoint destination,
        List<ItemChange> items,
        String sourceBefore,
        String sourceAfter,
        String destinationBefore,
        String destinationAfter,
        String sourceBeforeHash,
        String sourceAfterHash,
        String destinationBeforeHash,
        String destinationAfterHash,
        String action
) {
    public TransferLog {
        items = List.copyOf(items);
    }

    public record ItemChange(String itemKey, String itemData, int amount) {}
}
