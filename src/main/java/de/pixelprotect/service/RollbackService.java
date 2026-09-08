package de.pixelprotect.service;

import de.pixelprotect.model.AuditEntry;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
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
    private final Plugin plugin;
    private final AuditService audit;

    public RollbackService(Plugin plugin, AuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    public CompletableFuture<Result> rollback(List<AuditEntry> entries) {
        if (entries.isEmpty()) {
            return CompletableFuture.completedFuture(new Result(0, 0));
        }

        final Map<Long, List<AuditEntry>> byChunk = new HashMap<>();
        for (AuditEntry entry : entries) {
            byChunk.computeIfAbsent(chunkKey(entry.x() >> 4, entry.z() >> 4), ignored -> new ArrayList<>()).add(entry);
        }

        final CompletableFuture<Result> future = new CompletableFuture<>();
        final AtomicInteger remaining = new AtomicInteger(byChunk.size());
        final AtomicInteger applied = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final RegionScheduler scheduler = Bukkit.getRegionScheduler();

        for (List<AuditEntry> chunkEntries : byChunk.values()) {
            final AuditEntry first = chunkEntries.getFirst();
            final var world = Bukkit.getWorld(first.world());
            if (world == null) {
                skipped.addAndGet(chunkEntries.size());
                if (remaining.decrementAndGet() == 0) {
                    future.complete(new Result(applied.get(), skipped.get()));
                }
                continue;
            }

            final int chunkX = first.x() >> 4;
            final int chunkZ = first.z() >> 4;
            scheduler.run(plugin, world, chunkX, chunkZ, task -> {
                try {
                    for (AuditEntry entry : chunkEntries) {
                        final Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
                        if (!block.getBlockData().getAsString().equals(entry.afterData())) {
                            skipped.incrementAndGet();
                            continue;
                        }
                        if (!inventoryMatches(block, entry.afterInventory())) {
                            skipped.incrementAndGet();
                            continue;
                        }
                        audit.suppress(block);
                        final BlockData target = Bukkit.createBlockData(entry.beforeData());
                        block.setBlockData(target, false);
                        restoreInventory(block, entry.beforeInventory());
                        applied.incrementAndGet();
                    }
                } catch (RuntimeException exception) {
                    skipped.addAndGet(chunkEntries.size());
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        future.complete(new Result(applied.get(), skipped.get()));
                    }
                }
            });
        }
        return future;
    }

    private static boolean inventoryMatches(Block block, byte[] expected) {
        if (expected == null) {
            return true;
        }
        final var state = block.getState();
        if (!(state instanceof InventoryHolder holder)) {
            return false;
        }
        return Arrays.equals(expected, ItemStack.serializeItemsAsBytes(holder.getInventory().getContents()));
    }

    private static void restoreInventory(Block block, byte[] data) {
        if (data == null) {
            return;
        }
        final var state = block.getState();
        if (state instanceof InventoryHolder holder) {
            holder.getInventory().setContents(ItemStack.deserializeItemsFromBytes(data));
        }
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public record Result(int applied, int skipped) {}
}
