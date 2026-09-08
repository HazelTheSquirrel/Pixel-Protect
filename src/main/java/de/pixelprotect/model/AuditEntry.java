package de.pixelprotect.model;

import java.util.UUID;

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
        byte[] afterInventory
) {}
