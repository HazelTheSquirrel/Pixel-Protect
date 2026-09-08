package de.pixelprotect.service;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.model.EntitySnapshot;
import de.pixelprotect.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Conflict-aware, persistent rollback engine with non-blocking region-thread world mutation. */
public final class RollbackService {
    private static final Set<ActionType> BLOCK_ACTIONS = EnumSet.of(
            ActionType.BREAK, ActionType.PLACE, ActionType.BURN, ActionType.EXPLOSION,
            ActionType.PISTON, ActionType.FLUID, ActionType.GROW, ActionType.FORM,
            ActionType.SPREAD, ActionType.ENTITY_CHANGE, ActionType.BUCKET, ActionType.CONTAINER);
    private static final Set<ActionType> ENTITY_ACTIONS = EnumSet.of(
            ActionType.ITEM_DROP, ActionType.ITEM_PICKUP, ActionType.ITEM_DESPAWN,
            ActionType.ENTITY_SPAWN, ActionType.ENTITY_DEATH, ActionType.ENTITY_REMOVE,
            ActionType.PROJECTILE);

    private final Plugin plugin;
    private final AuditService audit;
    private final Database database;
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger activeJobs = new AtomicInteger();
    private final int maxConcurrentJobs;

    public RollbackService(Plugin plugin, AuditService audit, Database database) {
        this(plugin, audit, database, 1);
    }

    public RollbackService(Plugin plugin, AuditService audit, Database database, int maxConcurrentJobs) {
        this.plugin = plugin;
        this.audit = audit;
        this.database = database;
        this.maxConcurrentJobs = Math.max(1, maxConcurrentJobs);
    }

    public CompletableFuture<Result> preview(List<AuditEntry> entries) {
        return evaluate(List.copyOf(entries), false, null, false);
    }

    public CompletableFuture<Result> rollback(List<AuditEntry> entries) {
        return evaluate(List.copyOf(entries), true, null, false);
    }

    public CompletableFuture<JobSnapshot> start(List<AuditEntry> entries) {
        List<AuditEntry> ordered = List.copyOf(entries);
        if (ordered.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Keine Protokolleinträge für die Rücksetzung vorhanden."));
        }
        if (activeJobs.incrementAndGet() > maxConcurrentJobs) {
            activeJobs.decrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("Es läuft bereits die maximal zulässige Anzahl an Rücksetzaufträgen."));
        }

        UUID id = UUID.randomUUID();
        Job job = new Job(id, ordered.size(), ordered);
        jobs.put(id, job);
        return database.createRollbackJob(id, ordered)
                .thenCompose(ignored -> evaluate(ordered, true, job, false))
                .thenCompose(result -> finishJob(job, result))
                .thenApply(this::snapshot)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        job.status.set(Status.FAILED);
                        job.error = root(failure);
                        database.updateRollbackJob(job.id, Status.FAILED.name(), job.processed.get(), job.applied.get(),
                                job.skipped.get(), job.error);
                    }
                    activeJobs.decrementAndGet();
                });
    }

    public JobSnapshot status(UUID id) {
        Job j = jobs.get(id);
        return j == null ? null : snapshot(j);
    }

    public CompletableFuture<JobSnapshot> statusAsync(UUID id) {
        Job j = jobs.get(id);
        return j != null
                ? CompletableFuture.completedFuture(snapshot(j))
                : database.rollbackJob(id).thenApply(r -> r == null ? null : toSnapshot(r));
    }

    public boolean cancel(UUID id) {
        Job j = jobs.get(id);
        if (j == null || j.status.get() != Status.RUNNING) return false;
        j.cancelled.set(true);
        return true;
    }

    public CompletableFuture<Boolean> cancelAsync(UUID id) {
        Job j = jobs.get(id);
        if (j == null) {
            return database.rollbackJob(id).thenApply(r -> r != null && Status.RUNNING.name().equals(r.status()));
        }
        if (j.status.get() != Status.RUNNING) return CompletableFuture.completedFuture(false);
        j.cancelled.set(true);
        return CompletableFuture.completedFuture(true);
    }

    public CompletableFuture<Result> restore(UUID id) {
        Job local = jobs.get(id);
        CompletableFuture<Database.RollbackJobRecord> jobFuture = local == null
                ? database.rollbackJob(id)
                : CompletableFuture.completedFuture(new Database.RollbackJobRecord(
                        id, local.status.get().name(), local.total, local.processed.get(), local.applied.get(),
                        local.skipped.get(), local.error, 0, 0));
        return jobFuture.thenCompose(record -> {
            if (record == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Rücksetzauftrag nicht gefunden."));
            if (!Status.COMPLETED.name().equals(record.status())) {
                return CompletableFuture.failedFuture(new IllegalStateException("Nur vollständig abgeschlossene Rücksetzaufträge können wiederhergestellt werden."));
            }
            return database.appliedRollbackEntries(id).thenCompose(entries -> {
                if (entries.isEmpty()) return CompletableFuture.completedFuture(new Result(0, 0));
                return database.createRestore(id).thenCompose(restoreId ->
                        evaluate(entries, true, null, true)
                                .thenCompose(result -> database.updateRestore(
                                        restoreId,
                                        result.skipped() == 0 ? "COMPLETED" : "PARTIAL",
                                        result.applied(), result.skipped(),
                                        result.skipped() == 0 ? null : "Eine oder mehrere Wiederherstellungsprüfungen haben die Umkehrung abgelehnt.")
                                        .thenApply(ignored -> result))
                                .exceptionallyCompose(failure -> database.updateRestore(
                                                restoreId, "FAILED", 0, 0, root(failure))
                                        .thenCompose(ignored -> CompletableFuture.failedFuture(failure))));
            });
        });
    }

    private CompletableFuture<Job> finishJob(Job job, Result result) {
        Status finalStatus = job.status.get();
        if (finalStatus == Status.RUNNING) finalStatus = job.cancelled.get() ? Status.CANCELLED : Status.COMPLETED;
        job.status.set(finalStatus);
        return database.updateRollbackJob(job.id, finalStatus.name(), job.processed.get(), job.applied.get(),
                job.skipped.get(), job.error).thenApply(ignored -> job);
    }

    private CompletableFuture<Result> evaluate(List<AuditEntry> entries, boolean mutate, Job job, boolean inverse) {
        if (entries.isEmpty()) return CompletableFuture.completedFuture(new Result(0, 0));

        Map<ChunkKey, List<AuditEntry>> groups = new LinkedHashMap<>();
        int unsupported = 0;
        for (AuditEntry entry : entries) {
            if (!BLOCK_ACTIONS.contains(entry.action()) && !ENTITY_ACTIONS.contains(entry.action())) {
                unsupported++;
                continue;
            }
            groups.computeIfAbsent(new ChunkKey(entry.world(), entry.x() >> 4, entry.z() >> 4), ignored -> new ArrayList<>()).add(entry);
        }

        if (job != null) {
            job.status.set(Status.RUNNING);
            job.skipped.addAndGet(unsupported);
            job.processed.addAndGet(unsupported);
        }

        CompletableFuture<Result> future = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(groups.size());
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger(unsupported);
        List<Long> appliedIds = java.util.Collections.synchronizedList(new ArrayList<>());

        if (groups.isEmpty()) {
            future.complete(new Result(0, unsupported));
            return future;
        }

        RegionScheduler scheduler = Bukkit.getRegionScheduler();
        for (Map.Entry<ChunkKey, List<AuditEntry>> group : groups.entrySet()) {
            ChunkKey key = group.getKey();
            List<AuditEntry> list = group.getValue();
            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                skipped.addAndGet(list.size());
                if (job != null) {
                    job.skipped.addAndGet(list.size());
                    job.processed.addAndGet(list.size());
                }
                finishGroup(future, remaining, applied, skipped, appliedIds, job, mutate, inverse);
                continue;
            }

            scheduler.run(plugin, world, key.x(), key.z(), task -> {
                int index = 0;
                for (AuditEntry entry : list) {
                    if (job != null && job.cancelled.get()) {
                        int rest = list.size() - index;
                        skipped.addAndGet(rest);
                        job.skipped.addAndGet(rest);
                        job.processed.addAndGet(rest);
                        break;
                    }
                    try {
                        boolean ok = ENTITY_ACTIONS.contains(entry.action())
                                ? processEntity(world, entry, mutate, inverse)
                                : processBlock(world, entry, mutate, inverse);
                        if (ok) {
                            applied.incrementAndGet();
                            if (job != null) job.applied.incrementAndGet();
                            if (job != null && mutate && !inverse) appliedIds.add(entry.id());
                        } else {
                            skipped.incrementAndGet();
                            if (job != null) job.skipped.incrementAndGet();
                        }
                    } catch (RuntimeException ex) {
                        skipped.incrementAndGet();
                        if (job != null) {
                            job.skipped.incrementAndGet();
                            job.error = root(ex);
                        }
                    }
                    index++;
                    if (job != null) job.processed.incrementAndGet();
                }
                finishGroup(future, remaining, applied, skipped, appliedIds, job, mutate, inverse);
            });
        }
        return future;
    }

    private void finishGroup(CompletableFuture<Result> future,
                             AtomicInteger remaining,
                             AtomicInteger applied,
                             AtomicInteger skipped,
                             List<Long> appliedIds,
                             Job job,
                             boolean mutate,
                             boolean inverse) {
        if (remaining.decrementAndGet() != 0) return;
        if (job != null && mutate && !inverse && !appliedIds.isEmpty()) {
            List<Long> ids;
            synchronized (appliedIds) {
                ids = List.copyOf(appliedIds);
            }
            database.markRollbackApplied(job.id, ids).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    job.status.set(Status.FAILED);
                    job.error = root(failure);
                    future.completeExceptionally(failure);
                } else {
                    future.complete(new Result(applied.get(), skipped.get()));
                }
            });
        } else {
            future.complete(new Result(applied.get(), skipped.get()));
        }
    }

    private boolean processBlock(World world, AuditEntry entry, boolean mutate, boolean inverse) {
        var block = world.getBlockAt(entry.x(), entry.y(), entry.z());
        String expected = inverse ? entry.beforeData() : entry.afterData();
        byte[] expectedInventory = inverse ? entry.beforeInventory() : entry.afterInventory();
        String expectedEntity = inverse ? entry.beforeBlockEntity() : entry.afterBlockEntity();

        if (!safeBlockData(block).equals(expected)
                || !inventoryMatches(block, expectedInventory)
                || !BlockSnapshot.matchesBlockEntity(block.getState(), expectedEntity)) {
            return false;
        }
        if (!mutate) return true;

        BlockSnapshot target = new BlockSnapshot(
                inverse ? entry.afterData() : entry.beforeData(),
                inverse ? entry.afterInventory() : entry.beforeInventory(),
                inverse ? entry.afterBlockEntity() : entry.beforeBlockEntity());
        audit.suppress(block);
        block.setBlockData(Bukkit.createBlockData(target.blockData()), false);
        restoreInventory(block, target.inventory());
        BlockSnapshot.applyBlockEntity(block.getState(), target.blockEntity());
        return true;
    }

    private boolean processEntity(World world, AuditEntry entry, boolean mutate, boolean inverse) {
        EntitySnapshot expected = EntitySnapshot.parse(inverse ? entry.beforeData() : entry.afterData());
        EntitySnapshot target = EntitySnapshot.parse(inverse ? entry.afterData() : entry.beforeData());
        Entity found = expected == null || expected.uuid() == null ? null : world.getEntity(expected.uuid());
        if (expected != null && found == null) return false;
        if (!mutate) return true;
        if (found != null) found.remove();
        if (target == null) return true;

        EntityType type = target.entityType();
        if (type == null || type == EntityType.PLAYER) return false;
        Entity spawned = world.spawnEntity(target.location(world), type);
        if (target.nameComponent() != null) spawned.customName(target.nameComponent());
        if (spawned instanceof org.bukkit.entity.Item item && target.itemData() != null) {
            try {
                item.setItemStack(ItemStack.deserializeBytes(Base64.getDecoder().decode(target.itemData())));
            } catch (RuntimeException ignored) {
            }
        }
        spawned.setRotation(target.yaw(), target.pitch());
        spawned.setVelocity(new Vector(target.velocityX(), target.velocityY(), target.velocityZ()));
        spawned.setFireTicks(target.fireTicks());
        spawned.setFreezeTicks(target.freezeTicks());
        spawned.setTicksLived(target.ticksLived());
        spawned.setGlowing(target.glowing());
        spawned.setInvisible(target.invisible());
        spawned.setInvulnerable(target.invulnerable());
        spawned.setSilent(target.silent());
        spawned.setGravity(target.gravity());
        spawned.setPersistent(target.persistent());
        return true;
    }

    private static String safeBlockData(org.bukkit.block.Block block) {
        try {
            return block.getBlockData().getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean inventoryMatches(org.bukkit.block.Block block, byte[] expected) {
        if (expected == null) return true;
        try {
            return block.getState() instanceof InventoryHolder holder
                    && Arrays.equals(expected, ItemStack.serializeItemsAsBytes(holder.getInventory().getContents()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void restoreInventory(org.bukkit.block.Block block, byte[] data) {
        if (data == null) return;
        try {
            if (block.getState() instanceof InventoryHolder holder) {
                holder.getInventory().setContents(ItemStack.deserializeItemsFromBytes(data));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private JobSnapshot snapshot(Job job) {
        return new JobSnapshot(job.id, job.status.get(), job.total, job.processed.get(), job.applied.get(), job.skipped.get(), job.error);
    }

    private static JobSnapshot toSnapshot(Database.RollbackJobRecord record) {
        try {
            return new JobSnapshot(UUID.fromString(record.id().toString()), Status.valueOf(record.status()), record.total(),
                    record.processed(), record.applied(), record.skipped(), record.error());
        } catch (IllegalArgumentException ignored) {
            return new JobSnapshot(record.id(), Status.FAILED, record.total(), record.processed(), record.applied(),
                    record.skipped(), record.error() == null ? "Unbekannter Rücksetzstatus." : record.error());
        }
    }

    private static String root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record ChunkKey(UUID world, int x, int z) {}
    public record Result(int applied, int skipped) {}
    public record JobSnapshot(UUID id, Status status, int total, int processed, int applied, int skipped, String error) {}
    public enum Status { RUNNING, COMPLETED, CANCELLED, FAILED }

    private static final class Job {
        final UUID id;
        final int total;
        final List<AuditEntry> entries;
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger applied = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<Status> status = new AtomicReference<>(Status.RUNNING);
        volatile String error;

        Job(UUID id, int total, List<AuditEntry> entries) {
            this.id = id;
            this.total = total;
            this.entries = entries;
        }
    }
}
