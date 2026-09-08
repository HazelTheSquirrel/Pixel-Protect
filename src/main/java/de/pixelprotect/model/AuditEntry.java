package de.pixelprotect.model;

import java.util.Arrays;
import java.util.UUID;

/** Immutable persisted forensic event. State snapshots describe reversible state; details describe the event itself. */
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
        String details,
        UUID transactionId,
        long sequence
) {
    public AuditEntry {
        actorName = actorName == null ? "Unbekannt" : actorName;
        beforeData = beforeData == null ? "minecraft:air" : beforeData;
        afterData = afterData == null ? "minecraft:air" : afterData;
        beforeInventory = copy(beforeInventory);
        afterInventory = copy(afterInventory);
        details = details == null || details.isBlank() ? null : details;
        transactionId = transactionId;
    }

    public AuditEntry(long id, long time, UUID world, int x, int y, int z, UUID actor, String actorName,
                      ActionType action, String beforeData, String afterData, byte[] beforeInventory, byte[] afterInventory,
                      String beforeBlockEntity, String afterBlockEntity) {
        this(id, time, world, x, y, z, actor, actorName, action, beforeData, afterData,
                beforeInventory, afterInventory, beforeBlockEntity, afterBlockEntity, null, null, 0L);
    }

    public AuditEntry(long id, long time, UUID world, int x, int y, int z, UUID actor, String actorName,
                      ActionType action, String beforeData, String afterData, byte[] beforeInventory, byte[] afterInventory,
                      String beforeBlockEntity, String afterBlockEntity, UUID transactionId, long sequence) {
        this(id, time, world, x, y, z, actor, actorName, action, beforeData, afterData,
                beforeInventory, afterInventory, beforeBlockEntity, afterBlockEntity, null, transactionId, sequence);
    }

    public byte[] beforeInventoryCopy() { return copy(beforeInventory); }
    public byte[] afterInventoryCopy() { return copy(afterInventory); }

    private static byte[] copy(byte[] value) { return value == null ? null : Arrays.copyOf(value, value.length); }
}
