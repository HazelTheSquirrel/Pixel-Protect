package de.pixelprotect.service;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RollbackService {
    private static final java.util.Set<ActionType> ROLLBACKABLE = EnumSet.of(
            ActionType.BREAK, ActionType.PLACE, ActionType.BURN, ActionType.EXPLOSION,
            ActionType.PISTON, ActionType.FLUID, ActionType.GROW, ActionType.FORM,
            ActionType.SPREAD, ActionType.ENTITY_CHANGE, ActionType.BUCKET, ActionType.CONTAINER);

    private final Plugin plugin;
    private final AuditService audit;
    private final Database database;
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();

    public RollbackService(Plugin plugin, AuditService audit, Database database) {
        this.plugin = plugin;
        this.audit = audit;
        this.database = database;
    }

    public CompletableFuture<Result> preview(List<AuditEntry> entries) {
        return evaluate(entries, false, null, false);
    }

    public CompletableFuture<Result> rollback(List<AuditEntry> entries) {
        return evaluate(entries, true, null, false);
    }

    public CompletableFuture<JobSnapshot> start(List<AuditEntry> entries) {
        final UUID id = UUID.randomUUID();
        final Job job = new Job(id, entries.size(), List.copyOf(entries));
        jobs.put(id, job);
        return database.createRollbackJob(id, entries)
                .thenCompose(ignored -> evaluate(entries, true, job, false))
                .thenCompose(result -> finishJob(job, result))
                .thenApply(ignored -> snapshot(job))
                .whenComplete((snapshot, throwable) -> {
                    if (throwable != null) {
                        job.status.set(Status.FAILED);
                        job.error = rootMessage(throwable);
                        database.updateRollbackJob(job.id, job.status.get().name(), job.processed.get(), job.applied.get(), job.skipped.get(), job.error);
                    }
                });
    }

    public JobSnapshot status(UUID id) {
        final Job job = jobs.get(id);
        return job == null ? null : snapshot(job);
    }

    public CompletableFuture<JobSnapshot> statusAsync(UUID id) {
        final Job local = jobs.get(id);
        if (local != null) return CompletableFuture.completedFuture(snapshot(local));
        return database.rollbackJob(id).thenApply(record -> record == null ? null : toSnapshot(record));
    }

    public boolean cancel(UUID id) {
        final Job job = jobs.get(id);
        if (job == null || job.status.get() != Status.RUNNING) return false;
        job.cancelled.set(true);
        return true;
    }

    public CompletableFuture<Boolean> cancelAsync(UUID id) {
        final Job job = jobs.get(id);
        if (job == null) return database.rollbackJob(id).thenApply(record -> record != null && "RUNNING".equals(record.status()));
        if (job.status.get() != Status.RUNNING) return CompletableFuture.completedFuture(false);
        job.cancelled.set(true);
        return CompletableFuture.completedFuture(true);
    }

    public CompletableFuture<Result> restore(UUID id) {
        final Job local = jobs.get(id);
        final CompletableFuture<Database.RollbackJobRecord> jobFuture = local == null
                ? database.rollbackJob(id)
                : CompletableFuture.completedFuture(new Database.RollbackJobRecord(id, local.status.get().name(), local.total,
                local.processed.get(), local.applied.get(), local.skipped.get(), local.error, 0L, 0L));

        return jobFuture.thenCompose(record -> {
            if (record == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Rollback job not found."));
            if (!"COMPLETED".equals(record.status())) return CompletableFuture.failedFuture(new IllegalStateException("Only completed rollback jobs can be restored."));
            return database.appliedRollbackEntries(id).thenCompose(entries -> {
                if (entries.isEmpty()) return CompletableFuture.completedFuture(new Result(0, 0));
                return database.createRestore(id).thenCompose(restoreId ->
                        evaluate(entries, true, null, true)
                                .thenCompose(result -> database.updateRestore(restoreId, "COMPLETED", result.applied(), result.skipped(), null).thenApply(ignored -> result))
                                .exceptionallyCompose(throwable -> database.updateRestore(restoreId, "FAILED", 0, 0, rootMessage(throwable)).thenCompose(ignored -> CompletableFuture.failedFuture(throwable))));
            });
        });
    }

    private CompletableFuture<Job> finishJob(Job job, Result result) {
        if (job.cancelled.get()) job.status.set(Status.CANCELLED);
        else job.status.set(Status.COMPLETED);
        return database.updateRollbackJob(job.id, job.status.get().name(), job.processed.get(), job.applied.get(), job.skipped.get(), job.error)
                .thenApply(ignored -> job);
    }

    private CompletableFuture<Result> evaluate(List<AuditEntry> entries, boolean mutate, Job job, boolean inverse) {
        if (entries.isEmpty()) return CompletableFuture.completedFuture(new Result(0, 0));

        final Map<ChunkKey, List<AuditEntry>> groups = new HashMap<>();
        int unsupported = 0;
        for (AuditEntry entry : entries) {
            if (!ROLLBACKABLE.contains(entry.action())) {
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
        if (groups.isEmpty()) return CompletableFuture.completedFuture(new Result(0, unsupported));

        final CompletableFuture<Result> future = new CompletableFuture<>();
        final AtomicInteger left = new AtomicInteger(groups.size());
        final AtomicInteger applied = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger(unsupported);
        final RegionScheduler scheduler = Bukkit.getRegionScheduler();

        for (var group : groups.entrySet()) {
            final ChunkKey key = group.getKey();
            final List<AuditEntry> list = group.getValue();
            final var world = Bukkit.getWorld(key.world());
            if (world == null) {
                skipped.addAndGet(list.size());
                if (job != null) {
                    job.skipped.addAndGet(list.size());
                    job.processed.addAndGet(list.size());
                }
                finish(future, left, applied, skipped);
                continue;
            }

            scheduler.run(plugin, world, key.chunkX(), key.chunkZ(), task -> {
                final List<Long> newlyApplied = new ArrayList<>();
                int index = 0;
                for (AuditEntry entry : list) {
                    if (job != null && job.cancelled.get()) {
                        final int remaining = list.size() - index;
                        skipped.addAndGet(remaining);
                        job.skipped.addAndGet(remaining);
                        job.processed.addAndGet(remaining);
                        break;
                    }
                    try {
                        final Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
                        final String expected = inverse ? entry.beforeData() : entry.afterData();
                        final byte[] inventory = inverse ? entry.beforeInventory() : entry.afterInventory();
                        if (!block.getBlockData().getAsString().equals(expected) || !inventoryMatches(block, inventory)) {
                            skipped.incrementAndGet();
                            if (job != null) job.skipped.incrementAndGet();
                        } else {
                            final BlockData target = Bukkit.createBlockData(inverse ? entry.afterData() : entry.beforeData());
                            if (mutate) {
                                audit.suppress(block);
                                block.setBlockData(target, false);
                                restoreInventory(block, inverse ? entry.afterInventory() : entry.beforeInventory());
                                if (job != null && !inverse) newlyApplied.add(entry.id());
                            }
                            applied.incrementAndGet();
                            if (job != null) job.applied.incrementAndGet();
                        }
                    } catch (RuntimeException exception) {
                        skipped.incrementAndGet();
                        if (job != null) {
                            job.skipped.incrementAndGet();
                            job.error = rootMessage(exception);
                        }
                    }
                    index++;
                    if (job != null) job.processed.incrementAndGet();
                }

                if (job != null && !newlyApplied.isEmpty()) database.markRollbackApplied(job.id, newlyApplied);
                if (job != null && job.cancelled.get()) job.status.set(Status.CANCELLED);
                finish(future, left, applied, skipped);
            });
        }
        return future;
    }

    private static boolean inventoryMatches(Block block, byte[] expected) {
        if (expected == null) return true;
        final var state = block.getState();
        return state instanceof InventoryHolder holder
                && Arrays.equals(expected, ItemStack.serializeItemsAsBytes(holder.getInventory().getContents()));
    }

    private static void restoreInventory(Block block, byte[] data) {
        if (data == null) return;
        final var state = block.getState();
        if (state instanceof InventoryHolder holder) holder.getInventory().setContents(ItemStack.deserializeItemsFromBytes(data));
    }

    private static void finish(CompletableFuture<Result> future, AtomicInteger left, AtomicInteger applied, AtomicInteger skipped) {
        if (left.decrementAndGet() == 0) future.complete(new Result(applied.get(), skipped.get()));
    }

    private JobSnapshot snapshot(Job job) {
        return new JobSnapshot(job.id, job.status.get(), job.total, job.processed.get(), job.applied.get(), job.skipped.get(), job.error);
    }

    private static JobSnapshot toSnapshot(Database.RollbackJobRecord record) {
        return new JobSnapshot(record.id(), Status.valueOf(record.status()), record.total(), record.processed(), record.applied(), record.skipped(), record.error());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record ChunkKey(UUID world, int chunkX, int chunkZ) {}
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
