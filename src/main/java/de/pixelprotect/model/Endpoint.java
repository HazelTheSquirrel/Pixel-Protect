package de.pixelprotect.model;

import org.bukkit.Location;

import java.util.UUID;

public record Endpoint(EndpointType type, UUID entityId, UUID playerId, String world, int x, int y, int z, String label) {
    public static Endpoint player(UUID uuid, String name, Location location) {
        return new Endpoint(EndpointType.PLAYER, null, uuid, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), name);
    }

    public static Endpoint block(Location location, String label) {
        return new Endpoint(EndpointType.BLOCK_CONTAINER, null, null, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), label);
    }

    public static Endpoint entity(UUID uuid, Location location, String label) {
        return new Endpoint(EndpointType.MINECART, uuid, null, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), label);
    }

    public static Endpoint ground(Location location) {
        return new Endpoint(EndpointType.GROUND, null, null, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), "Boden");
    }

    public static Endpoint system() {
        return new Endpoint(EndpointType.SYSTEM, null, null, null, 0, 0, 0, "System");
    }

    public String identity() {
        return switch (type) {
            case PLAYER -> "player:" + playerId;
            case BLOCK_CONTAINER -> "block:" + world + ":" + x + ":" + y + ":" + z;
            case MINECART -> "entity:" + entityId;
            case GROUND -> "ground:" + world + ":" + x + ":" + y + ":" + z;
            case SYSTEM -> "system";
        };
    }
}
