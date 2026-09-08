package de.pixelprotect.service;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.model.EntitySnapshot;
import de.pixelprotect.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;

/** Konfliktbewusste, dauerhaft gespeicherte Rücksetzung mit regionssicherer Weltmutation. */
public final class RollbackService {
    private static final Set<ActionType> BLOCK_ACTIONS = EnumSet.of(ActionType.BREAK, ActionType.PLACE, ActionType.BURN, ActionType.EXPLOSION, ActionType.PISTON, ActionType.FLUID, ActionType.GROW, ActionType.FORM, ActionType.SPREAD, ActionType.ENTITY_CHANGE, ActionType.BUCKET, ActionType.CONTAINER);
    private static final Set<ActionType> ENTITY_ACTIONS = EnumSet.of(ActionType.ITEM_DROP, ActionType.ITEM_PICKUP, ActionType.ITEM_DESPAWN, ActionType.ENTITY_SPAWN, ActionType.ENTITY_DEATH, ActionType.ENTITY_REMOVE, ActionType.PROJECTILE);
    private final Plugin plugin;
    private final AuditService audit;
    private final Database database;
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();

    public RollbackService(Plugin plugin, AuditService audit, Database database) { this.plugin = plugin; this.audit = audit; this.database = database; }
    public CompletableFuture<Result> preview(List<AuditEntry> e) { return evaluate(e, false, null, false); }
    public CompletableFuture<Result> rollback(List<AuditEntry> e) { return evaluate(e, true, null, false); }
    public CompletableFuture<JobSnapshot> start(List<AuditEntry> e) { UUID id = UUID.randomUUID(); Job j = new Job(id, e.size(), List.copyOf(e)); jobs.put(id, j); return database.createRollbackJob(id, e).thenCompose(x -> evaluate(e, true, j, false)).thenCompose(r -> finishJob(j, r)).thenApply(this::snapshot); }
    public JobSnapshot status(UUID id) { Job j = jobs.get(id); return j == null ? null : snapshot(j); }
    public CompletableFuture<JobSnapshot> statusAsync(UUID id) { Job j = jobs.get(id); return j != null ? CompletableFuture.completedFuture(snapshot(j)) : database.rollbackJob(id).thenApply(r -> r == null ? null : toSnapshot(r)); }
    public boolean cancel(UUID id) { Job j = jobs.get(id); if (j == null || j.status.get() != Status.RUNNING) return false; j.cancelled.set(true); return true; }
    public CompletableFuture<Boolean> cancelAsync(UUID id) { Job j = jobs.get(id); if (j == null) return database.rollbackJob(id).thenApply(r -> r != null && "RUNNING".equals(r.status())); if (j.status.get() != Status.RUNNING) return CompletableFuture.completedFuture(false); j.cancelled.set(true); return CompletableFuture.completedFuture(true); }

    public CompletableFuture<Result> restore(UUID id) {
        Job local = jobs.get(id);
        CompletableFuture<Database.RollbackJobRecord> jf = local == null
                ? database.rollbackJob(id)
                : CompletableFuture.completedFuture(new Database.RollbackJobRecord(id, local.status.get().name(), local.total, local.processed.get(), local.applied.get(), local.skipped.get(), local.error, 0, 0));
        return jf.thenCompose(r -> {
            if (r == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Rücksetzauftrag nicht gefunden."));
            if (!"COMPLETED".equals(r.status())) return CompletableFuture.failedFuture(new IllegalStateException("Nur vollständig abgeschlossene Rücksetzaufträge können wiederhergestellt werden."));
            return database.appliedRollbackEntries(id).thenCompose(e -> {
                if (e.isEmpty()) return CompletableFuture.completedFuture(new Result(0, 0));
                return database.createRestore(id).thenCompose(restoreId -> evaluate(e, true, null, true)
                        .thenCompose(result -> database.updateRestore(restoreId, result.skipped() == 0 ? "COMPLETED" : "PARTIAL", result.applied(), result.skipped(), result.skipped() == 0 ? null : "Eine oder mehrere Wiederherstellungsprüfungen haben die Umkehrung abgelehnt.").thenApply(x -> result))
                        .exceptionallyCompose(t -> database.updateRestore(restoreId, "FAILED", 0, 0, root(t)).thenCompose(x -> CompletableFuture.failedFuture(t))));
            });
        });
    }

    private CompletableFuture<Job> finishJob(Job j, Result r) {
        Status finalStatus = j.status.get();
        if (finalStatus == Status.RUNNING) finalStatus = j.cancelled.get() ? Status.CANCELLED : Status.COMPLETED;
        j.status.set(finalStatus);
        return database.updateRollbackJob(j.id, finalStatus.name(), j.processed.get(), j.applied.get(), j.skipped.get(), j.error).thenApply(x -> j);
    }

    private CompletableFuture<Result> evaluate(List<AuditEntry> entries, boolean mutate, Job job, boolean inverse) {
        if (entries.isEmpty()) return CompletableFuture.completedFuture(new Result(0, 0));
        Map<ChunkKey, List<AuditEntry>> groups = new LinkedHashMap<>();
        int unsupported = 0;
        for (AuditEntry e : entries) {
            if (!BLOCK_ACTIONS.contains(e.action()) && !ENTITY_ACTIONS.contains(e.action())) { unsupported++; continue; }
            groups.computeIfAbsent(new ChunkKey(e.world(), e.x() >> 4, e.z() >> 4), k -> new ArrayList<>()).add(e);
        }
        if (job != null) { job.status.set(Status.RUNNING); job.skipped.addAndGet(unsupported); job.processed.addAndGet(unsupported); }
        CompletableFuture<Result> future = new CompletableFuture<>();
        AtomicInteger left = new AtomicInteger(groups.size()), applied = new AtomicInteger(), skipped = new AtomicInteger(unsupported);
        if (groups.isEmpty()) { future.complete(new Result(0, unsupported)); return future; }
        RegionScheduler scheduler = Bukkit.getRegionScheduler();
        for (var g : groups.entrySet()) {
            ChunkKey key = g.getKey(); List<AuditEntry> list = g.getValue(); World world = Bukkit.getWorld(key.world);
            if (world == null) { skipped.addAndGet(list.size()); if (job != null) { job.skipped.addAndGet(list.size()); job.processed.addAndGet(list.size()); } finish(future, left, applied, skipped); continue; }
            scheduler.run(plugin, world, key.x, key.z, task -> {
                List<Long> appliedIds = new ArrayList<>(); int index = 0;
                for (AuditEntry e : list) {
                    if (job != null && job.cancelled.get()) { int rem = list.size() - index; skipped.addAndGet(rem); job.skipped.addAndGet(rem); job.processed.addAndGet(rem); break; }
                    try {
                        boolean ok = ENTITY_ACTIONS.contains(e.action()) ? processEntity(world, e, mutate, inverse) : processBlock(world, e, mutate, inverse);
                        if (ok) { applied.incrementAndGet(); if (job != null) job.applied.incrementAndGet(); if (job != null && !inverse) appliedIds.add(e.id()); }
                        else { skipped.incrementAndGet(); if (job != null) job.skipped.incrementAndGet(); }
                    } catch (RuntimeException ex) { skipped.incrementAndGet(); if (job != null) { job.skipped.incrementAndGet(); job.error = root(ex); } }
                    index++; if (job != null) job.processed.incrementAndGet();
                }
                if (job != null && !appliedIds.isEmpty()) {
                    try { database.markRollbackApplied(job.id, appliedIds).join(); }
                    catch (RuntimeException ex) { job.error = root(ex); job.status.set(Status.FAILED); }
                }
                finish(future, left, applied, skipped);
            });
        }
        return future;
    }

    private boolean processBlock(World w, AuditEntry e, boolean mutate, boolean inverse) {
        var b = w.getBlockAt(e.x(), e.y(), e.z());
        String expected = inverse ? e.beforeData() : e.afterData();
        byte[] inv = inverse ? e.beforeInventory() : e.afterInventory();
        String be = inverse ? e.beforeBlockEntity() : e.afterBlockEntity();
        if (!safeBlockData(b).equals(expected) || !inventoryMatches(b, inv) || !BlockSnapshot.matchesBlockEntity(b.getState(), be)) return false;
        if (!mutate) return true;
        BlockSnapshot target = new BlockSnapshot(inverse ? e.afterData() : e.beforeData(), inverse ? e.afterInventory() : e.beforeInventory(), inverse ? e.afterBlockEntity() : e.beforeBlockEntity());
        audit.suppress(b);
        b.setBlockData(Bukkit.createBlockData(target.blockData()), false);
        restoreInventory(b, target.inventory());
        BlockSnapshot.applyBlockEntity(b.getState(), target.blockEntity());
        return true;
    }

    private boolean processEntity(World w, AuditEntry e, boolean mutate, boolean inverse) {
        EntitySnapshot expected = EntitySnapshot.parse(inverse ? e.beforeData() : e.afterData());
        EntitySnapshot target = EntitySnapshot.parse(inverse ? e.afterData() : e.beforeData());
        Entity found = expected == null || expected.uuid() == null ? null : w.getEntity(expected.uuid());
        if (expected != null && found == null) return false;
        if (!mutate) return true;
        if (found != null) found.remove();
        if (target == null) return true;
        EntityType type = target.entityType();
        if (type == null || type == EntityType.PLAYER) return false;
        Entity spawned = w.spawnEntity(target.location(w), type);
        if (target.nameComponent() != null) spawned.customName(target.nameComponent());
        if (spawned instanceof org.bukkit.entity.Item item && target.itemData() != null) {
            try { item.setItemStack(ItemStack.deserializeBytes(Base64.getDecoder().decode(target.itemData()))); } catch (RuntimeException ignored) { }
        }
        spawned.setRotation(target.yaw(), target.pitch());
        spawned.setVelocity(new org.bukkit.util.Vector(target.velocityX(), target.velocityY(), target.velocityZ()));
        spawned.setFireTicks(target.fireTicks()); spawned.setFreezeTicks(target.freezeTicks()); spawned.setTicksLived(target.ticksLived());
        spawned.setGlowing(target.glowing()); spawned.setInvisible(target.invisible()); spawned.setInvulnerable(target.invulnerable()); spawned.setSilent(target.silent());
        spawned.setGravity(target.gravity()); spawned.setPersistent(target.persistent());
        return true;
    }

    private static String safeBlockData(org.bukkit.block.Block block) {
        try { return block.getBlockData().getAsString(); } catch (RuntimeException ignored) { return ""; }
    }

    private static boolean inventoryMatches(org.bukkit.block.Block b, byte[] expected) {
        if (expected == null) return true;
        try { return b.getState() instanceof InventoryHolder h && Arrays.equals(expected, ItemStack.serializeItemsAsBytes(h.getInventory().getContents())); }
        catch (RuntimeException ignored) { return false; }
    }

    private static void restoreInventory(org.bukkit.block.Block b, byte[] data) {
        if (data == null) return;
        try { if (b.getState() instanceof InventoryHolder h) h.getInventory().setContents(ItemStack.deserializeItemsFromBytes(data)); }
        catch (RuntimeException ignored) { }
    }

    private static void finish(CompletableFuture<Result> f, AtomicInteger left, AtomicInteger a, AtomicInteger s) { if (left.decrementAndGet() == 0) f.complete(new Result(a.get(), s.get())); }
    private JobSnapshot snapshot(Job j) { return new JobSnapshot(j.id, j.status.get(), j.total, j.processed.get(), j.applied.get(), j.skipped.get(), j.error); }
    private static JobSnapshot toSnapshot(Database.RollbackJobRecord r) { try { return new JobSnapshot(r.id(), Status.valueOf(r.status()), r.total(), r.processed(), r.applied(), r.skipped(), r.error()); } catch (IllegalArgumentException ignored) { return new JobSnapshot(r.id(), Status.FAILED, r.total(), r.processed(), r.applied(), r.skipped(), r.error() == null ? "Unbekannter Rücksetzstatus." : r.error()); } }
    private static String root(Throwable t) { Throwable c = t; while (c.getCause() != null) c = c.getCause(); return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage(); }

    private record ChunkKey(UUID world, int x, int z) {}
    public record Result(int applied, int skipped) {}
    public record JobSnapshot(UUID id, Status status, int total, int processed, int applied, int skipped, String error) {}
    public enum Status { RUNNING, COMPLETED, CANCELLED, FAILED }
    private static final class Job {
        final UUID id; final int total; final List<AuditEntry> entries;
        final AtomicInteger processed = new AtomicInteger(), applied = new AtomicInteger(), skipped = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean(); final AtomicReference<Status> status = new AtomicReference<>(Status.RUNNING); volatile String error;
        Job(UUID id, int total, List<AuditEntry> e) { this.id = id; this.total = total; this.entries = e; }
    }
}
