package de.pixelprotect.service;

import de.pixelprotect.model.Actor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player-owned automation mechanisms and reconstructs the direct mechanism involved in
 * automated inventory transfers. Attribution is explicitly indirect: the owner is only used when
 * the mechanism itself was placed by that player and the transfer endpoint proves the mechanism.
 */
public final class AutomationTracker {
    private static final long TRANSFER_CONTEXT_TTL_MILLIS = 10 * 60_000L;
    private static final int MAX_TRANSFER_CONTEXTS = 10_000;

    private final Map<BlockKey, Mechanism> mechanisms = new ConcurrentHashMap<>();
    private final Map<UUID, TransferContext> transfers = new ConcurrentHashMap<>();

    public void recordPlacement(Block block, Player player) {
        if (block == null || player == null || !isMechanism(block.getType())) return;
        mechanisms.put(new BlockKey(block), new Mechanism(block.getType(),
                new Actor(player.getUniqueId(), player.getName()), System.currentTimeMillis()));
    }

    public void recordRemoval(Block block) {
        if (block != null) mechanisms.remove(new BlockKey(block));
    }

    public TransferContext trackTransfer(UUID transactionId, Inventory source, Inventory destination) {
        if (transactionId == null) return null;
        cleanupTransfers();
        Block sourceBlock = blockOf(source);
        Block destinationBlock = blockOf(destination);
        Block mechanismBlock = null;
        Mechanism mechanism = null;
        if (sourceBlock != null) {
            mechanism = mechanisms.get(new BlockKey(sourceBlock));
            if (mechanism != null) mechanismBlock = sourceBlock;
        }
        if (mechanism == null && destinationBlock != null) {
            mechanism = mechanisms.get(new BlockKey(destinationBlock));
            if (mechanism != null) mechanismBlock = destinationBlock;
        }
        if (mechanism == null) return null;

        TransferContext context = new TransferContext(transactionId, location(sourceBlock), location(destinationBlock),
                location(mechanismBlock), mechanism.type(), mechanism.owner(), "Hopper-Automatik", System.currentTimeMillis());
        if (transfers.size() >= MAX_TRANSFER_CONTEXTS) cleanupOldest();
        transfers.put(transactionId, context);
        return context;
    }

    public TransferContext context(UUID transactionId) {
        if (transactionId == null) return null;
        TransferContext context = transfers.get(transactionId);
        if (context == null || System.currentTimeMillis() - context.time() > TRANSFER_CONTEXT_TTL_MILLIS) {
            if (context != null) transfers.remove(transactionId, context);
            return null;
        }
        return context;
    }

    public int trackedMechanisms() {
        return mechanisms.size();
    }

    private void cleanupTransfers() {
        long cutoff = System.currentTimeMillis() - TRANSFER_CONTEXT_TTL_MILLIS;
        transfers.entrySet().removeIf(entry -> entry.getValue().time() < cutoff);
    }

    private void cleanupOldest() {
        UUID oldestId = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<UUID, TransferContext> entry : transfers.entrySet()) {
            if (entry.getValue().time() < oldest) {
                oldest = entry.getValue().time();
                oldestId = entry.getKey();
            }
        }
        if (oldestId != null) transfers.remove(oldestId);
    }

    private static boolean isMechanism(Material material) {
        return material == Material.HOPPER || material == Material.DROPPER || material == Material.DISPENSER
                || material == Material.CRAFTER;
    }

    private static Block blockOf(Inventory inventory) {
        if (inventory == null) return null;
        try {
            InventoryHolder holder = inventory.getHolder();
            if (holder instanceof BlockState state && state instanceof Container) return state.getBlock();
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static LocationData location(Block block) {
        if (block == null) return null;
        return new LocationData(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public record LocationData(UUID world, int x, int y, int z) {}

    public record TransferContext(UUID transactionId, LocationData source, LocationData destination,
                                  LocationData mechanism, Material mechanismType, Actor owner, String cause, long time) {
        public Actor attributedActor() {
            return owner == null ? Actor.environment() : owner;
        }
    }

    private record Mechanism(Material type, Actor owner, long placedAt) {}

    private record BlockKey(UUID world, int x, int y, int z) {
        BlockKey(Block block) {
            this(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
