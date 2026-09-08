package de.pixelprotect.model;

import java.util.UUID;

/** Immutable persisted audit record. Transaction identity makes multi-record events reversible as one unit. */
public record AuditEntry(
        long id,
        long time,
        UUID world,
        int x,
        int y,
        int z,
        UUID actor,
        String actorName,
        ActionType action,
        String beforeData,
        String afterData,
        byte[] beforeInventory,
        byte[] afterInventory,
        String beforeBlockEntity,
        String afterBlockEntity,
        UUID transactionId,
        long sequence
) {
    public AuditEntry(long id, long time, UUID world, int x, int y, int z, UUID actor, String actorName,
                      ActionType action, String beforeData, String afterData, byte[] beforeInventory, byte[] afterInventory,
                      String beforeBlockEntity, String afterBlockEntity) {
        this(id, time, world, x, y, z, actor, actorName, action, beforeData, afterData,
                beforeInventory, afterInventory, beforeBlockEntity, afterBlockEntity, null, 0L);
    }
}
