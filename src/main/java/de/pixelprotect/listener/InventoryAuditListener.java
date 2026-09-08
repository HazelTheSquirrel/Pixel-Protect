package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import de.pixelprotect.service.InventoryDiffService;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Forensic container audit listener. Player events snapshot before mutation at LOWEST and compare
 * against the MONITOR state. Automated inventory transfers are recorded as environment actions and
 * retain a shared transaction id so source and destination can be correlated without inventing a player.
 */
public final class InventoryAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;
    private final Map<Object, PendingEvent> pending = new IdentityHashMap<>();

    public InventoryAuditListener(Plugin plugin, AuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClickBefore(InventoryClickEvent event) {
        captureBefore(event, List.of(event.getView().getTopInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onClickAfter(InventoryClickEvent event) {
        finish(event, actor(event.getWhoClicked() instanceof Player player ? player : null));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDragBefore(InventoryDragEvent event) {
        captureBefore(event, List.of(event.getView().getTopInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDragAfter(InventoryDragEvent event) {
        finish(event, actor(event.getWhoClicked() instanceof Player player ? player : null));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMoveBefore(InventoryMoveItemEvent event) {
        captureBefore(event, List.of(event.getSource(), event.getDestination()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMoveAfter(InventoryMoveItemEvent event) {
        finish(event, Actor.environment());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPickupBefore(InventoryPickupItemEvent event) {
        captureBefore(event, List.of(event.getInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPickupAfter(InventoryPickupItemEvent event) {
        finish(event, Actor.environment());
    }

    private void captureBefore(Object event, List<Inventory> inventories) {
        List<Snapshot> snapshots = new ArrayList<>();
        for (Inventory inventory : inventories) {
            Snapshot snapshot = snapshot(inventory);
            if (snapshot != null) snapshots.add(snapshot);
        }
        synchronized (pending) {
            if (snapshots.isEmpty()) pending.remove(event);
            else pending.put(event, new PendingEvent(UUID.randomUUID(), snapshots));
        }
    }

    private void finish(Object event, Actor actor) {
        final PendingEvent state;
        synchronized (pending) {
            state = pending.remove(event);
        }
        if (state == null) return;

        try {
            for (int index = 0; index < state.before.size(); index++) {
                Snapshot before = state.before.get(index);
                Snapshot after = snapshot(before.block);
                if (after == null || !InventoryDiffService.hasInventoryChanges(before.inventoryContents, after.inventoryContents)) {
                    continue;
                }

                audit.record(before.block, ActionType.CONTAINER, actor,
                        before.snapshot, after.snapshot, state.transactionId, index);
            }
        } catch (RuntimeException ignored) {
            // Audit failures must never break a server event pipeline.
        }
    }

    private Snapshot snapshot(Inventory inventory) {
        if (inventory == null) return null;
        try {
            final BlockState state = inventory.getHolder() instanceof BlockState blockState ? blockState : null;
            if (!(state instanceof Container)) return null;
            final var block = state.getBlock();
            return snapshot(block);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Snapshot snapshot(org.bukkit.block.Block block) {
        try {
            if (!(block.getState() instanceof Container container)) return null;
            final BlockSnapshot snapshot = BlockSnapshot.capture(block);
            return new Snapshot(block, snapshot, cloneContents(container.getInventory().getContents()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ItemStack[] cloneContents(ItemStack[] source) {
        if (source == null) return new ItemStack[0];
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }

    private static Actor actor(Player player) {
        return player == null ? Actor.environment() : new Actor(player.getUniqueId(), player.getName());
    }

    private record PendingEvent(UUID transactionId, List<Snapshot> before) {
    }

    private record Snapshot(org.bukkit.block.Block block, BlockSnapshot snapshot, ItemStack[] inventoryContents) {
        private Snapshot {
            inventoryContents = cloneContents(inventoryContents);
        }
    }
}
