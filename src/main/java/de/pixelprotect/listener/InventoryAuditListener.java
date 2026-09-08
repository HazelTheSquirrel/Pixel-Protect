package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import de.pixelprotect.service.AutomationTracker;
import de.pixelprotect.service.InventoryDiffService;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Forensic container audit listener. Player events snapshot before mutation at LOWEST and compare
 * against MONITOR state. Automated transfers share one transaction id and are correlated with a
 * tracked hopper/dropper/dispenser/crafter when the endpoint proves the mechanism involved.
 */
public final class InventoryAuditListener implements Listener {
    private final AuditService audit;
    private final AutomationTracker automation;
    private final Map<Object, PendingEvent> pending = new IdentityHashMap<>();

    public InventoryAuditListener(Plugin plugin, AuditService audit) {
        this(audit, new AutomationTracker());
    }

    public InventoryAuditListener(AuditService audit, AutomationTracker automation) {
        this.audit = audit;
        this.automation = automation;
    }

    public AutomationTracker automation() {
        return automation;
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
        UUID transactionId = UUID.randomUUID();
        AutomationTracker.TransferContext context = automation.trackTransfer(transactionId, event.getSource(), event.getDestination());
        captureBefore(event, List.of(event.getSource(), event.getDestination()), transactionId, context);
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
        captureBefore(event, inventories, UUID.randomUUID(), null);
    }

    private void captureBefore(Object event, List<Inventory> inventories, UUID transactionId,
                               AutomationTracker.TransferContext context) {
        List<Snapshot> snapshots = new ArrayList<>();
        for (Inventory inventory : inventories) {
            Snapshot snapshot = snapshot(inventory);
            if (snapshot != null) snapshots.add(snapshot);
        }
        synchronized (pending) {
            if (snapshots.isEmpty()) pending.remove(event);
            else pending.put(event, new PendingEvent(transactionId, context, snapshots));
        }
    }

    private void finish(Object event, Actor defaultActor) {
        final PendingEvent state;
        synchronized (pending) {
            state = pending.remove(event);
        }
        if (state == null) return;

        try {
            Actor actor = state.context == null ? defaultActor : state.context.attributedActor();
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
            InventoryHolder holder = inventory.getHolder();
            if (holder instanceof BlockState state && state instanceof Container) return snapshot(state.getBlock());
            if (holder instanceof DoubleChest doubleChest) {
                InventoryHolder left = doubleChest.getLeftSide();
                if (left instanceof BlockState state && state instanceof Container) return snapshot(state.getBlock());
                InventoryHolder right = doubleChest.getRightSide();
                if (right instanceof BlockState state && state instanceof Container) return snapshot(state.getBlock());
            }
            return null;
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

    private record PendingEvent(UUID transactionId, AutomationTracker.TransferContext context, List<Snapshot> before) {}

    private record Snapshot(org.bukkit.block.Block block, BlockSnapshot snapshot, ItemStack[] inventoryContents) {
        private Snapshot {
            inventoryContents = cloneContents(inventoryContents);
        }
    }
}
