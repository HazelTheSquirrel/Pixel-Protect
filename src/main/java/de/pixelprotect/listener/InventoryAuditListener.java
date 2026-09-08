package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import de.pixelprotect.service.AutomationTracker;
import de.pixelprotect.service.InventoryDiffService;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
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

/** Forensic container audit listener with Folia-safe asynchronous attribution. */
public final class InventoryAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;
    private final AutomationTracker automation;
    private final Map<Object, PendingEvent> pending = new IdentityHashMap<>();

    public InventoryAuditListener(Plugin plugin, AuditService audit, AutomationTracker automation) {
        this.plugin = plugin;
        this.audit = audit;
        this.automation = automation;
    }

    public AutomationTracker automation() { return automation; }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClickBefore(InventoryClickEvent event) { captureBefore(event, List.of(event.getView().getTopInventory())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onClickAfter(InventoryClickEvent event) { finish(event, actor(event.getWhoClicked() instanceof Player player ? player : null)); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDragBefore(InventoryDragEvent event) { captureBefore(event, List.of(event.getView().getTopInventory())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDragAfter(InventoryDragEvent event) { finish(event, actor(event.getWhoClicked() instanceof Player player ? player : null)); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMoveBefore(InventoryMoveItemEvent event) {
        UUID transactionId = UUID.randomUUID();
        AutomationTracker.TransferContext context = automation.trackTransfer(transactionId, event.getSource(), event.getDestination());
        captureBefore(event, List.of(event.getSource(), event.getDestination()), transactionId, context);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMoveAfter(InventoryMoveItemEvent event) { finish(event, Actor.environment()); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPickupBefore(InventoryPickupItemEvent event) { captureBefore(event, List.of(event.getInventory())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPickupAfter(InventoryPickupItemEvent event) { finish(event, Actor.environment()); }

    private void captureBefore(Object event, List<Inventory> inventories) { captureBefore(event, inventories, UUID.randomUUID(), null); }

    private void captureBefore(Object event, List<Inventory> inventories, UUID transactionId, AutomationTracker.TransferContext context) {
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
        synchronized (pending) { state = pending.remove(event); }
        if (state == null) return;
        try {
            List<ChangedSnapshot> changes = changedSnapshots(state);
            if (changes.isEmpty()) return;
            if (state.context != null && state.context.owner() == null && state.context.mechanism() != null) {
                Block mechanism = resolveMechanism(state.context.mechanism());
                if (mechanism != null) {
                    audit.latestPlacementActor(mechanism).whenComplete((owner, failure) -> {
                        Actor attributed = owner == null || failure != null ? defaultActor : owner;
                        if (owner != null && failure == null) automation.cacheOwner(state.transactionId, owner);
                        scheduleRecordChanges(changes, attributed, state.transactionId);
                    });
                    return;
                }
            }
            Actor actor = state.context == null ? defaultActor : state.context.attributedActor();
            scheduleRecordChanges(changes, actor, state.transactionId);
        } catch (RuntimeException ignored) {
        }
    }

    private void scheduleRecordChanges(List<ChangedSnapshot> changes, Actor actor, UUID transactionId) {
        for (ChangedSnapshot change : changes) {
            UUID worldId = change.world();
            int x = change.x(), y = change.y(), z = change.z();
            Bukkit.getRegionScheduler().run(plugin, worldId, x >> 4, z >> 4, task -> {
                var world = Bukkit.getWorld(worldId);
                if (world == null) return;
                Block block = world.getBlockAt(x, y, z);
                audit.record(block, ActionType.CONTAINER, actor, change.before().snapshot(), change.after().snapshot(), transactionId, change.sequence());
            });
        }
    }

    private List<ChangedSnapshot> changedSnapshots(PendingEvent state) {
        List<ChangedSnapshot> changes = new ArrayList<>();
        for (int index = 0; index < state.before.size(); index++) {
            Snapshot before = state.before.get(index);
            Snapshot after = snapshot(before.block);
            if (after == null || !InventoryDiffService.hasInventoryChanges(before.inventoryContents, after.inventoryContents)) continue;
            Block block = before.block;
            changes.add(new ChangedSnapshot(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), before, after, index));
        }
        return List.copyOf(changes);
    }

    private Block resolveMechanism(AutomationTracker.LocationData location) {
        if (location == null || location.world() == null) return null;
        try {
            var world = Bukkit.getWorld(location.world());
            return world == null ? null : world.getBlockAt(location.x(), location.y(), location.z());
        } catch (RuntimeException ignored) { return null; }
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
        } catch (RuntimeException ignored) { return null; }
    }

    private Snapshot snapshot(Block block) {
        try {
            if (!(block.getState() instanceof Container container)) return null;
            BlockSnapshot snapshot = BlockSnapshot.capture(block);
            return new Snapshot(block, snapshot, cloneContents(container.getInventory().getContents()));
        } catch (RuntimeException ignored) { return null; }
    }

    private static ItemStack[] cloneContents(ItemStack[] source) {
        if (source == null) return new ItemStack[0];
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }
    private static Actor actor(Player player) { return player == null ? Actor.environment() : new Actor(player.getUniqueId(), player.getName()); }

    private record PendingEvent(UUID transactionId, AutomationTracker.TransferContext context, List<Snapshot> before) {}
    private record Snapshot(Block block, BlockSnapshot snapshot, ItemStack[] inventoryContents) {
        private Snapshot { inventoryContents = cloneContents(inventoryContents); }
    }
    private record ChangedSnapshot(UUID world, int x, int y, int z, Snapshot before, Snapshot after, long sequence) {}
}
