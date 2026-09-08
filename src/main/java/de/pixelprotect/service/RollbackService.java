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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public final class RollbackService {
    private static final java.util.Set<ActionType> ROLLBACKABLE = java.util.EnumSet.of(
            ActionType.BREAK, ActionType.PLACE, ActionType.BURN, ActionType.EXPLOSION,
            ActionType.PISTON, ActionType.FLUID, ActionType.GROW, ActionType.FORM,
            ActionType.SPREAD, ActionType.ENTITY_CHANGE, ActionType.BUCKET
    );

    private final Plugin plugin;
    private final de.pixelprotect.service.AuditService audit;

    public RollbackService(Plugin plugin, de.pixelprotect.service.AuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    public CompletableFuture<Result> preview(List<AuditEntry> entries) {
        return evaluate(entries, false);
    }

    public CompletableFuture<Result> rollback(List<AuditEntry> entries) {
        return evaluate(entries, true);
    }

    private CompletableFuture<Result> evaluate(List<AuditEntry> entries, boolean mutate) {
        if (entries.isEmpty()) return CompletableFuture.completedFuture(new Result(0, 0));
        final Map<ChunkKey, List<AuditEntry>> byChunk = new HashMap<>();
        int unsupported = 0;
        for (AuditEntry entry : entries) {
            if (!ROLLBACKABLE.contains(entry.action())) {
                unsupported++;
                continue;
            }
            byChunk.computeIfAbsent(new ChunkKey(entry.world(), entry.x() >> 4, entry.z() >> 4), ignored -> new ArrayList<>()).add(entry);
        }

        final int initialSkipped = unsupported;
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
                completeIfFinished(future, remaining, applied, skipped);
                continue;
            }
            scheduler.run(plugin, world, key.chunkX(), key.chunkZ(), task -> {
                for (AuditEntry entry : chunkEntries) {
                    try {
                        final Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
                        if (!matchesRecordedState(block, entry)) {
                            skipped.incrementAndGet();
                            continue;
                        }
                        if (mutate) {
                            final BlockData target = Bukkit.createBlockData(entry.beforeData());
                            audit.suppress(block);
                            block.setBlockData(target, false);
                            restoreInventory(block, entry.beforeInventory());
                        }
                        applied.incrementAndGet();
                    } catch (RuntimeException exception) {
                        skipped.incrementAndGet();
                    }
                }
                completeIfFinished(future, remaining, applied, skipped);
            });
        }
        return future;
    }

    private static boolean matchesRecordedState(Block block, AuditEntry entry) {
        if (!block.getBlockData().getAsString().equals(entry.afterData())) return false;
        return inventoryMatches(block, entry.afterInventory());
    }

    private static void completeIfFinished(CompletableFuture<Result> future, AtomicInteger remaining,
                                           AtomicInteger applied, AtomicInteger skipped) {
        if (remaining.decrementAndGet() == 0) future.complete(new Result(applied.get(), skipped.get()));
    }

    private static boolean inventoryMatches(Block block, byte[] expected) {
        if (expected == null) return true;
        final var state = block.getState();
        if (!(state instanceof InventoryHolder holder)) return false;
        return Arrays.equals(expected, ItemStack.serializeItemsAsBytes(holder.getInventory().getContents()));
    }

    private static void restoreInventory(Block block, byte[] data) {
        if (data == null) return;
        final var state = block.getState();
        if (state instanceof InventoryHolder holder) holder.getInventory().setContents(ItemStack.deserializeItemsFromBytes(data));
    }

    private record ChunkKey(UUID world, int chunkX, int chunkZ) {}
    public record Result(int applied, int skipped) {}
}
