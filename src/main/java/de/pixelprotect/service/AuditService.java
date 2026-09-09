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

/** Synchronous event-thread capture facade. It creates immutable records and never performs database I/O. */
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
        return world != null && !excludedWorlds.contains(world) && (includedWorlds.isEmpty() || includedWorlds.contains(world));
    }

    public CompletableFuture<Actor> latestPlacementActor(Block block) {
        if (block == null || !isWorldIncluded(block.getWorld().getUID())) return CompletableFuture.completedFuture(null);
        long now = clock.millis();
        AuditQuery query = new AuditQuery(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), 0,
                0L, now, null, Set.of(ActionType.PLACE), Set.of(), Set.of(), Set.of(), 1);
        return database.query(query).thenApply(entries -> {
            if (entries.isEmpty()) return null;
            AuditEntry entry = entries.getFirst();
            return entry.actor() == null ? null : new Actor(entry.actor(), entry.actorName());
        });
    }

    public boolean record(Block block, ActionType action, Actor actor, BlockSnapshot before, BlockSnapshot after) {
        return record(block, action, actor, before, after, null, newTransaction(), 0L);
    }

    public boolean record(Block block, ActionType action, Actor actor, BlockSnapshot before, BlockSnapshot after,
                          UUID transactionId, long sequence) {
        return record(block, action, actor, before, after, null, transactionId, sequence);
    }

    public boolean record(Block block, ActionType action, Actor actor, BlockSnapshot before, BlockSnapshot after,
                          String details, UUID transactionId, long sequence) {
        if (block == null || action == null || actor == null || !isWorldIncluded(block.getWorld().getUID()) || isSuppressed(block)) return false;
        cleanupSuppression(clock.millis());
        return database.record(new AuditEntry(0L, clock.millis(), block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(),
                actor.uuid(), actor.name(), action, before.blockData(), after.blockData(), before.inventory(), after.inventory(),
                before.blockEntity(), after.blockEntity(), details, transactionId, sequence));
    }

    /** Records a WorldEdit mutation without touching Bukkit world state from the WorldEdit extent thread. */
    public boolean recordWorldEdit(UUID world, int x, int y, int z, Actor actor, String beforeData, String afterData,
                                   String details, UUID transactionId, long sequence) {
        if (!isWorldIncluded(world) || beforeData == null || afterData == null || beforeData.equals(afterData)) return false;
        Actor effectiveActor = actor == null ? Actor.environment() : actor;
        boolean beforeAir = beforeData.startsWith("minecraft:air");
        boolean afterAir = afterData.startsWith("minecraft:air");
        ActionType action = afterAir ? ActionType.BREAK : beforeAir ? ActionType.PLACE : ActionType.WORLD_EDIT;
        return database.record(new AuditEntry(0L, clock.millis(), world, x, y, z, effectiveActor.uuid(), effectiveActor.name(), action,
                beforeData, afterData, null, null, null, null, details, transactionId, sequence));
    }

    public boolean recordPlayer(Block block, ActionType action, Player player, BlockSnapshot before, BlockSnapshot after) {
        return recordPlayer(block, action, player, before, after, null, newTransaction(), 0L);
    }

    public boolean recordPlayer(Block block, ActionType action, Player player, BlockSnapshot before, BlockSnapshot after,
                                UUID transactionId, long sequence) {
        return recordPlayer(block, action, player, before, after, null, transactionId, sequence);
    }

    public boolean recordPlayer(Block block, ActionType action, Player player, BlockSnapshot before, BlockSnapshot after,
                                String details, UUID transactionId, long sequence) {
        if (player == null) return false;
        return record(block, action, new Actor(player.getUniqueId(), player.getName()), before, after, details, transactionId, sequence);
    }

    public boolean recordEntity(Block block, ActionType action, Entity entity, BlockSnapshot before, BlockSnapshot after) {
        return recordEntity(block, action, entity, before, after, null, newTransaction(), 0L);
    }

    public boolean recordEntity(Block block, ActionType action, Entity entity, BlockSnapshot before, BlockSnapshot after,
                                UUID transactionId, long sequence) {
        return recordEntity(block, action, entity, before, after, null, transactionId, sequence);
    }

    public boolean recordEntity(Block block, ActionType action, Entity entity, BlockSnapshot before, BlockSnapshot after,
                                String details, UUID transactionId, long sequence) {
        Actor actor = entity == null ? Actor.environment() : new Actor(entity.getUniqueId(), entity.getType().getKey().toString());
        return record(block, action, actor, before, after, details, transactionId, sequence);
    }

    public boolean recordEnvironment(Block block, ActionType action, BlockSnapshot before, BlockSnapshot after) {
        return recordEnvironment(block, action, before, after, null, newTransaction(), 0L);
    }

    public boolean recordEnvironment(Block block, ActionType action, BlockSnapshot before, BlockSnapshot after,
                                     String details, UUID transactionId, long sequence) {
        return record(block, action, Actor.environment(), before, after, details, transactionId, sequence);
    }

    /** Suppresses the plugin's own rollback mutation for a short, location-scoped interval. */
    public void suppress(Block block) {
        if (block != null) suppressed.put(new BlockKey(block), clock.millis() + 2_000L);
    }

    private boolean isSuppressed(Block block) {
        BlockKey key = new BlockKey(block);
        Long expires = suppressed.get(key);
        if (expires == null) return false;
        if (expires >= clock.millis()) return true;
        suppressed.remove(key, expires);
        return false;
    }

    private void cleanupSuppression(long now) {
        if (suppressed.size() < 256) return;
        suppressed.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        private BlockKey(Block block) { this(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    }
}
