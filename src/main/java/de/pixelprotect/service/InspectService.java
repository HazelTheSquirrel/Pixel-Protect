package de.pixelprotect.service;

import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.AuditQuery;
import de.pixelprotect.storage.Database;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class InspectService {
    private final Database database;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    public InspectService(Database database) {
        this.database = database;
    }

    public boolean toggle(Player player) {
        if (!enabled.add(player.getUniqueId())) {
            enabled.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public boolean isEnabled(Player player) {
        return enabled.contains(player.getUniqueId());
    }

    public void disable(Player player) {
        enabled.remove(player.getUniqueId());
    }

    public CompletableFuture<java.util.List<AuditEntry>> lookup(Player player) {
        final var block = player.getTargetBlockExact(8);
        if (block == null) return CompletableFuture.completedFuture(java.util.List.of());
        return lookup(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public CompletableFuture<java.util.List<AuditEntry>> lookup(UUID world, int x, int y, int z) {
        final long now = System.currentTimeMillis();
        return database.query(new AuditQuery(world, x, y, z, 0,
                now - 7L * 86_400_000L, now, null,
                Set.of(), Set.of(), Set.of(), Set.of(), 15));
    }
}
