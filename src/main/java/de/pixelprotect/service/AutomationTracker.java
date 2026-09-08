package de.pixelprotect.service;

import de.pixelprotect.model.Actor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
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
    private final Map<BlockKey, HopperLink> hopperLinks = new ConcurrentHashMap<>();
    private final Map<UUID, TransferContext> transfers = new ConcurrentHashMap<>();

    public void recordPlacement(Block block, Player player) {
        if (block == null || player == null || !isMechanism(block.getType())) return;
        mechanisms.put(new BlockKey(block), new Mechanism(block.getType(),
                new Actor(player.getUniqueId(), player.getName()), System.currentTimeMillis()));
    }

    public void recordRemoval(Block block) {
        if (block == null) return;
        BlockKey key = new BlockKey(block);
        mechanisms.remove(key);
        hopperLinks.remove(key);
    }

    public void recordHopperSearch(HopperInventorySearchEvent event) {
        if (event == null || event.getBlock() == null || event.getSearchBlock() == null) return;
        Block hopper = event.getBlock();
        Block search = event.getSearchBlock();
        hopperLinks.put(new BlockKey(hopper), new HopperLink(new LocationData(search.getWorld().getUID(),
                search.getX(), search.getY(), search.getZ()), event.getContainerType(), System.currentTimeMillis()));
    }

    public HopperLink hopperLink(Block hopper) {
        if (hopper == null) return null;
        BlockKey key = new BlockKey(hopper);
        HopperLink link = hopperLinks.get(key);
        if (link == null || System.currentTimeMillis() - link.time() > TRANSFER_CONTEXT_TTL_MILLIS) {
            if (link != null) hopperLinks.remove(key, link);
            return null;
        }
        return link;
    }

    public TransferContext trackTransfer(UUID transactionId, Inventory source, Inventory destination) {
        if (transactionId == null) return null;
        cleanupTransfers();
        Block sourceBlock = blockOf(source);
        Block destinationBlock = blockOf(destination);
        Block mechanismBlock = null;
        Mechanism mechanism = null;

        if (sourceBlock != null && isMechanism(sourceBlock.getType())) {
            mechanismBlock = sourceBlock;
            mechanism = mechanisms.get(new BlockKey(sourceBlock));
        }
        if (mechanismBlock == null && destinationBlock != null && isMechanism(destinationBlock.getType())) {
            mechanismBlock = destinationBlock;
            mechanism = mechanisms.get(new BlockKey(destinationBlock));
        }
        if (mechanismBlock == null) return null;

        LocationData sourceLocation = location(sourceBlock);
        LocationData destinationLocation = location(destinationBlock);
        if (mechanismBlock.getType() == Material.HOPPER) {
            HopperLink link = hopperLink(mechanismBlock);
            if (link != null) {
                if (sourceBlock != null && sourceBlock.equals(mechanismBlock)
                        && link.containerType() == HopperInventorySearchEvent.ContainerType.DESTINATION) {
                    destinationLocation = link.searchBlock();
                }
                if (destinationBlock != null && destinationBlock.equals(mechanismBlock)
                        && link.containerType() == HopperInventorySearchEvent.ContainerType.SOURCE) {
                    sourceLocation = link.searchBlock();
                }
            }
        }

        Actor owner = mechanism == null ? null : mechanism.owner();
        TransferContext context = new TransferContext(transactionId, sourceLocation, destinationLocation,
                location(mechanismBlock), mechanismBlock.getType(), owner, "Hopper-Automatik", System.currentTimeMillis());
        if (transfers.size() >= MAX_TRANSFER_CONTEXTS) cleanupOldest();
        transfers.put(transactionId, context);
        return context;
    }

    public void cacheOwner(UUID transactionId, Actor owner) {
        if (transactionId == null || owner == null || owner.uuid() == null) return;
        transfers.computeIfPresent(transactionId, (id, old) -> old.withOwner(owner));
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
        hopperLinks.entrySet().removeIf(entry -> entry.getValue().time() < cutoff);
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

    public record HopperLink(LocationData searchBlock, HopperInventorySearchEvent.ContainerType containerType, long time) {}

    public record TransferContext(UUID transactionId, LocationData source, LocationData destination,
                                  LocationData mechanism, Material mechanismType, Actor owner, String cause, long time) {
        public Actor attributedActor() {
            return owner == null ? Actor.environment() : owner;
        }

        public TransferContext withOwner(Actor actor) {
            return new TransferContext(transactionId, source, destination, mechanism, mechanismType, actor, cause, time);
        }
    }

    private record Mechanism(Material type, Actor owner, long placedAt) {}

    private record BlockKey(UUID world, int x, int y, int z) {
        BlockKey(Block block) {
            this(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
