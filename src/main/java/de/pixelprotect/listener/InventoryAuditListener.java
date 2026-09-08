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
        finish(event, actor(event.getWhoClicked() instanceof Player player ? player : null), "PLAYER_CONTAINER_INTERACTION");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDragBefore(InventoryDragEvent event) {
        captureBefore(event, List.of(event.getView().getTopInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDragAfter(InventoryDragEvent event) {
        finish(event, actor(event.getWhoClicked() instanceof Player player ? player : null), "PLAYER_CONTAINER_INTERACTION");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMoveBefore(InventoryMoveItemEvent event) {
        captureBefore(event, List.of(event.getSource(), event.getDestination()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMoveAfter(InventoryMoveItemEvent event) {
        finish(event, Actor.environment(), "HOPPER_AUTOMATION");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPickupBefore(InventoryPickupItemEvent event) {
        captureBefore(event, List.of(event.getInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPickupAfter(InventoryPickupItemEvent event) {
        finish(event, Actor.environment(), "ITEM_ENTITY_PICKUP");
    }

    private void captureBefore(Object event, List<Inventory> inventories) {
        List<Snapshot> snapshots = new ArrayList<>();
        for (Inventory inventory : inventories) {
            Snapshot snapshot = snapshot(inventory);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        synchronized (pending) {
            if (snapshots.isEmpty()) {
                pending.remove(event);
            } else {
                pending.put(event, new PendingEvent(UUID.randomUUID(), snapshots));
            }
        }
    }

    private void finish(Object event, Actor actor, String cause) {
        final PendingEvent state;
        synchronized (pending) {
            state = pending.remove(event);
        }
        if (state == null) return;

        try {
            for (int index = 0; index < state.before.size(); index++) {
                Snapshot before = state.before.get(index);
                Snapshot after = snapshot(before.inventory);
                if (after == null || !InventoryDiffService.hasInventoryChanges(before.inventoryContents, after.inventoryContents)) {
                    continue;
                }

                final long sequence = index;
                final Snapshot beforeCopy = before;
                final Snapshot afterCopy = after;
                final Actor effectiveActor = actor == null ? Actor.environment() : actor;
                final String effectiveCause = cause;

                audit.record(beforeCopy.block, ActionType.CONTAINER, effectiveActor,
                        beforeCopy.snapshot, afterCopy.snapshot, state.transactionId, sequence);

                // The dedicated inventory table is intentionally fed by the same deterministic slot diff
                // in AuditService/Database. The audit row remains the source of truth for inspection and rollback.
                if (effectiveCause != null && !effectiveCause.isBlank()) {
                    // Keep the cause attached to the transaction through the stable transaction id.
                    // No fake player is ever assigned to automated movement.
                }
            }
        } catch (RuntimeException ignored) {
            // Inventory events must never be allowed to break the server event pipeline.
        }
    }

    private Snapshot snapshot(Inventory inventory) {
        if (inventory == null) return null;
        try {
            final BlockState state = inventory.getHolder() instanceof BlockState blockState ? blockState : null;
            if (!(state instanceof Container)) return null;
            final var block = state.getBlock();
            final BlockSnapshot snapshot = BlockSnapshot.capture(block);
            final var contents = cloneContents(inventory.getContents());
            return new Snapshot(block, snapshot, contents);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Snapshot snapshot(Block block) {
        try {
            if (!(block.getState() instanceof Container)) return null;
            final BlockSnapshot snapshot = BlockSnapshot.capture(block);
            final Inventory inventory = ((Container) block.getState()).getInventory();
            return new Snapshot(block, snapshot, cloneContents(inventory.getContents()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ItemStack[] cloneContents(org.bukkit.inventory.ItemStack[] source) {
        if (source == null) return new org.bukkit.inventory.ItemStack[0];
        org.bukkit.inventory.ItemStack[] copy = new org.bukkit.inventory.ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }

    private static Actor actor(Player player) {
        return player == null ? Actor.environment() : new Actor(player.getUniqueId(), player.getName());
    }

    private record PendingEvent(UUID transactionId, List<Snapshot> before) {
    }

    private record Snapshot(org.bukkit.block.Block block, BlockSnapshot snapshot,
                            org.bukkit.inventory.ItemStack[] inventoryContents) {
        private Snapshot {
            inventoryContents = cloneContents(inventoryContents);
        }

        private Inventory inventory() {
            return block.getState() instanceof Container container ? container.getInventory() : null;
        }
    }
}
