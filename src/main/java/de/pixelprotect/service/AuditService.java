package de.pixelprotect.service;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.storage.Database;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuditService {
    private final Database database;
    private final Clock clock;
    private final ConcurrentHashMap<BlockKey, Long> suppressed = new ConcurrentHashMap<>();

    public AuditService(Database database) { this(database, Clock.systemUTC()); }
    AuditService(Database database, Clock clock) { this.database = database; this.clock = clock; }

    public boolean record(Block block, ActionType action, Actor actor, BlockSnapshot before, BlockSnapshot after) {
        if (isSuppressed(block)) return false;
        return database.record(new AuditEntry(0L, clock.millis(), block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(),
                actor.uuid(), actor.name(), action, before.blockData(), after.blockData(), before.inventory(), after.inventory(),
                before.blockEntity(), after.blockEntity()));
    }

    public void suppress(Block block) { suppressed.put(new BlockKey(block), clock.millis() + 2_000L); }

    private boolean isSuppressed(Block block) {
        final BlockKey key = new BlockKey(block);
        final Long expires = suppressed.get(key);
        if (expires == null) return false;
        if (expires >= clock.millis()) return true;
        suppressed.remove(key, expires);
        return false;
    }

    public boolean recordPlayer(Block block, ActionType action, Player player, BlockSnapshot before, BlockSnapshot after) {
        return record(block, action, new Actor(player.getUniqueId(), player.getName()), before, after);
    }
    public boolean recordEntity(Block block, ActionType action, Entity entity, BlockSnapshot before, BlockSnapshot after) {
        return record(block, action, new Actor(entity.getUniqueId(), entity.getName()), before, after);
    }
    public boolean recordEnvironment(Block block, ActionType action, BlockSnapshot before, BlockSnapshot after) {
        return record(block, action, Actor.environment(), before, after);
    }
    private record BlockKey(UUID world, int x, int y, int z) {
        private BlockKey(Block block) { this(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    }
}
