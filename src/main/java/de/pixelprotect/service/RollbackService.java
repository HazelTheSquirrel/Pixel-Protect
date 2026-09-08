package de.pixelprotect.service;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
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
            ActionType.SPREAD, ActionType.ENTITY_CHANGE, ActionType.BUCKET, ActionType.CONTAINER
    );

    private final Plugin plugin;
    private final AuditService audit;
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();

    public RollbackService(Plugin plugin, AuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    public CompletableFuture<Result> preview(List<AuditEntry> entries) { return evaluate(entries, false, null); }
    public CompletableFuture<Result> rollback(List<AuditEntry> entries) { return evaluate(entries, true, null); }

    public CompletableFuture<JobSnapshot> start(List<AuditEntry> entries) {
        final UUID id = UUID.randomUUID();
        final Job job = new Job(id, entries.size(), List.copyOf(entries));
        jobs.put(id, job);
        evaluate(entries, true, job).whenComplete((result, throwable) -> {
            if (throwable != null) { job.status.set(Status.FAILED); job.error = rootMessage(throwable); }
            else if (job.cancelled.get()) job.status.set(Status.CANCELLED);
            else job.status.set(Status.COMPLETED);
            job.snapshot = snapshot(job);
        });
        return CompletableFuture.completedFuture(snapshot(job));
    }

    public JobSnapshot status(UUID id) {
        final Job job = jobs.get(id);
        return job == null ? null : snapshot(job);
    }

    public boolean cancel(UUID id) {
        final Job job = jobs.get(id);
        if (job == null || job.status.get() != Status.RUNNING) return false;
        job.cancelled.set(true);
        return true;
    }

    public CompletableFuture<Result> restore(UUID id) {
        final Job job = jobs.get(id);
        if (job == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Rollback job not found."));
        if (job.status.get() != Status.COMPLETED) return CompletableFuture.failedFuture(new IllegalStateException("Only completed rollback jobs can be restored."));
        return evaluate(job.entries, false, null, true);
    }

    private CompletableFuture<Result> evaluate(List<AuditEntry> entries, boolean mutate, Job job) {
        return evaluate(entries, mutate, job, false);
    }

    private CompletableFuture<Result> evaluate(List<AuditEntry> entries, boolean mutate, Job job, boolean inverse) {
        if (entries.isEmpty()) return CompletableFuture.completedFuture(new Result(0, 0));
        final Map<ChunkKey, List<AuditEntry>> byChunk = new HashMap<>();
        int unsupported = 0;
        for (AuditEntry entry : entries) {
            if (!ROLLBACKABLE.contains(entry.action())) { unsupported++; continue; }
            byChunk.computeIfAbsent(new ChunkKey(entry.world(), entry.x() >> 4, entry.z() >> 4), ignored -> new ArrayList<>()).add(entry);
        }
        final int initialSkipped = unsupported;
        if (job != null) { job.status.set(Status.RUNNING); job.skipped.addAndGet(initialSkipped); }
        if (byChunk.isEmpty()) return CompletableFuture.completedFuture(new Result(0, initialSkipped));

        final CompletableFuture<Result> future = new CompletableFuture<>();
        final AtomicInteger remaining = new AtomicInteger(byChunk.size());
        final AtomicInteger applied = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger(initialSkipped);
        final RegionScheduler scheduler = Bukkit.getRegionScheduler();

        for (Map.Entry<ChunkKey, List<AuditEntry>> group : byChunk.entrySet()) {
            final ChunkKey key = group.getKey();
            final List<AuditEntry> chunkEntries = group.getValue();
            final var world = Bukkit.getWorld(key.world());
            if (world == null) {
                skipped.addAndGet(chunkEntries.size());
                if (job != null) job.skipped.addAndGet(chunkEntries.size());
                completeIfFinished(future, remaining, applied, skipped);
                continue;
            }
            scheduler.run(plugin, world, key.chunkX(), key.chunkZ(), task -> {
                int index = 0;
                for (AuditEntry entry : chunkEntries) {
                    if (job != null && job.cancelled.get()) {
                        final int left = chunkEntries.size() - index;
                        skipped.addAndGet(left);
                        job.skipped.addAndGet(left);
                        job.processed.addAndGet(left);
                        break;
                    }
                    try {
                        final Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
                        final String expectedData = inverse ? entry.beforeData() : entry.afterData();
                        final byte[] expectedInventory = inverse ? entry.beforeInventory() : entry.afterInventory();
                        if (!block.getBlockData().getAsString().equals(expectedData) || !inventoryMatches(block, expectedInventory)) {
                            skipped.incrementAndGet();
                            if (job != null) job.skipped.incrementAndGet();
                        } else {
                            if (mutate) {
                                final BlockData target = Bukkit.createBlockData(inverse ? entry.afterData() : entry.beforeData());
                                audit.suppress(block);
                                block.setBlockData(target, false);
                                restoreInventory(block, inverse ? entry.afterInventory() : entry.beforeInventory());
                                if (job != null && !inverse) job.appliedEntries.add(entry);
                            }
                            applied.incrementAndGet();
                            if (job != null) job.applied.incrementAndGet();
                        }
                    } catch (RuntimeException exception) {
                        skipped.incrementAndGet();
                        if (job != null) { job.skipped.incrementAndGet(); job.error = exception.getMessage(); }
                    }
                    index++;
                    if (job != null) job.processed.incrementAndGet();
                }
                if (job != null && job.cancelled.get()) job.status.set(Status.CANCELLED);
                completeIfFinished(future, remaining, applied, skipped);
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

    private static void completeIfFinished(CompletableFuture<Result> future, AtomicInteger remaining,
                                           AtomicInteger applied, AtomicInteger skipped) {
        if (remaining.decrementAndGet() == 0) future.complete(new Result(applied.get(), skipped.get()));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private JobSnapshot snapshot(Job job) {
        return new JobSnapshot(job.id, job.status.get(), job.total, job.processed.get(), job.applied.get(), job.skipped.get(), job.error);
    }

    private record ChunkKey(UUID world, int chunkX, int chunkZ) {}
    public record Result(int applied, int skipped) {}
    public record JobSnapshot(UUID id, Status status, int total, int processed, int applied, int skipped, String error) {}
    public enum Status { RUNNING, COMPLETED, CANCELLED, FAILED }

    private static final class Job {
        private final UUID id;
        private final int total;
        private final List<AuditEntry> entries;
        private final List<AuditEntry> appliedEntries = java.util.Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger applied = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Status> status = new AtomicReference<>(Status.RUNNING);
        private volatile String error;
        private volatile JobSnapshot snapshot;
        private Job(UUID id, int total, List<AuditEntry> entries) { this.id = id; this.total = total; this.entries = entries; }
    }
}
