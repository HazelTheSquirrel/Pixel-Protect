package de.pixelprotect.service;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.AuditQuery;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.storage.Database;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Transaction-aware audit facade with configurable world boundaries. */
public final class AuditService {
    private final Database database;
    private final Clock clock;
    private final Set<UUID> includedWorlds;
    private final Set<UUID> excludedWorlds;
    private final ConcurrentHashMap<BlockKey, Long> suppressed = new ConcurrentHashMap<>();

    public AuditService(Database database) { this(database, Clock.systemUTC(), Set.of(), Set.of()); }
    public AuditService(Database database, Set<UUID> includedWorlds, Set<UUID> excludedWorlds) {
        this(database, Clock.systemUTC(), includedWorlds, excludedWorlds);
    }
    AuditService(Database database, Clock clock) { this(database, clock, Set.of(), Set.of()); }
    AuditService(Database database, Clock clock, Set<UUID> includedWorlds, Set<UUID> excludedWorlds) {
        this.database = database;
        this.clock = clock;
        this.includedWorlds = Set.copyOf(includedWorlds);
        this.excludedWorlds = Set.copyOf(excludedWorlds);
    }

    public UUID newTransaction() { return UUID.randomUUID(); }

    public boolean isWorldIncluded(UUID world) {
        return !excludedWorlds.contains(world) && (includedWorlds.isEmpty() || includedWorlds.contains(world));
    }

    public CompletableFuture<Actor> latestPlacementActor(Block block) {
        if (block == null || !isWorldIncluded(block.getWorld().getUID())) {
            return CompletableFuture.completedFuture(null);
        }
        long now = clock.millis();
        AuditQuery query = new AuditQuery(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), 0,
                0L, now, null, Set.of(ActionType.PLACE), Set.of(), Set.of(), Set.of(), 1);
        return database.query(query).thenApply(entries -> entries.isEmpty()
                ? null
                : new Actor(entries.getFirst().actor(), entries.getFirst().actorName()));
    }

    public boolean record(Block block, ActionType action, Actor actor, BlockSnapshot before, BlockSnapshot after) {
        return record(block, action, actor, before, after, newTransaction(), 0L);
    }

    public boolean record(Block block, ActionType action, Actor actor, BlockSnapshot before, BlockSnapshot after,
                          UUID transactionId, long sequence) {
        if (!isWorldIncluded(block.getWorld().getUID()) || isSuppressed(block)) return false;
        return database.record(new AuditEntry(0L, clock.millis(), block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(),
                actor.uuid(), actor.name(), action, before.blockData(), after.blockData(), before.inventory(), after.inventory(),
                before.blockEntity(), after.blockEntity(), transactionId, sequence));
    }

    public boolean recordPlayer(Block block, ActionType action, Player player, BlockSnapshot before, BlockSnapshot after) {
        return recordPlayer(block, action, player, before, after, newTransaction(), 0L);
    }

    public boolean recordPlayer(Block block, ActionType action, Player player, BlockSnapshot before, BlockSnapshot after,
                                UUID transactionId, long sequence) {
        return record(block, action, new Actor(player.getUniqueId(), player.getName()), before, after, transactionId, sequence);
    }

    public boolean recordEntity(Block block, ActionType action, Entity entity, BlockSnapshot before, BlockSnapshot after) {
        return recordEntity(block, action, entity, before, after, newTransaction(), 0L);
    }

    public boolean recordEntity(Block block, ActionType action, Entity entity, BlockSnapshot before, BlockSnapshot after,
                                UUID transactionId, long sequence) {
        return record(block, action, new Actor(entity.getUniqueId(), entity.getName()), before, after, transactionId, sequence);
    }

    public boolean recordEnvironment(Block block, ActionType action, BlockSnapshot before, BlockSnapshot after) {
        return record(block, action, Actor.environment(), before, after, newTransaction(), 0L);
    }

    public void suppress(Block block) {
        suppressed.put(new BlockKey(block), clock.millis() + 2_000L);
    }

    private boolean isSuppressed(Block block) {
        BlockKey key = new BlockKey(block);
        Long expires = suppressed.get(key);
        if (expires == null) return false;
        if (expires >= clock.millis()) return true;
        suppressed.remove(key, expires);
        return false;
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        private BlockKey(Block block) { this(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    }
}
