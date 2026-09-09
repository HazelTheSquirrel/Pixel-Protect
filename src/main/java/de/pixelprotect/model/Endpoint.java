package de.pixelprotect.model;

import java.util.UUID;

public record Endpoint(
        EndpointType type,
        UUID entityId,
        UUID playerId,
        String world,
        int x,
        int y,
        int z,
        String label
) {
    public static Endpoint player(UUID id, String name) {
        return new Endpoint(EndpointType.PLAYER, null, id, null, 0, 0, 0, name);
    }

    public static Endpoint block(EndpointType type, String world, int x, int y, int z, String label) {
        return new Endpoint(type, null, null, world, x, y, z, label);
    }

    public static Endpoint entity(EndpointType type, UUID entityId, String world, int x, int y, int z, String label) {
        return new Endpoint(type, entityId, null, world, x, y, z, label);
    }

    public String key() {
        if (entityId != null) return type + ":entity:" + entityId;
        if (playerId != null) return type + ":player:" + playerId;
        return type + ":block:" + world + ":" + x + ":" + y + ":" + z;
    }
}
