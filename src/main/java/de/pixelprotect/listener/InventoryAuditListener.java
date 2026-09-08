package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import org.bukkit.Bukkit;
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

public final class InventoryAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;

    public InventoryAuditListener(Plugin plugin, AuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        final Actor actor = event.getWhoClicked() instanceof Player player
                ? new Actor(player.getUniqueId(), player.getName()) : Actor.environment();
        captureLater(event.getView().getTopInventory(), actor);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        final Actor actor = event.getWhoClicked() instanceof Player player
                ? new Actor(player.getUniqueId(), player.getName()) : Actor.environment();
        captureLater(event.getView().getTopInventory(), actor);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        captureLater(event.getSource(), Actor.environment());
        captureLater(event.getDestination(), Actor.environment());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(InventoryPickupItemEvent event) {
        captureLater(event.getInventory(), Actor.environment());
    }

    private void captureLater(Inventory inventory, Actor actor) {
        final BlockState state = inventory.getHolder() instanceof BlockState blockState ? blockState : null;
        if (!(state instanceof Container container)) return;
        final var block = container.getBlock();
        final BlockSnapshot before = BlockSnapshot.capture(block);
        Bukkit.getRegionScheduler().run(plugin, block.getWorld(), block.getX() >> 4, block.getZ() >> 4, task -> {
            final BlockSnapshot after = BlockSnapshot.capture(block);
            if (!before.equals(after)) audit.record(block, ActionType.CONTAINER, actor, before, after);
        });
    }
}
