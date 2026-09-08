package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import de.pixelprotect.service.InventoryDiffService;
import io.papermc.paper.event.block.CrafterCraftEvent;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.plugin.Plugin;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Covers container mutations which do not reliably pass through InventoryMoveItemEvent. */
public final class ContainerProcessingAuditListener implements Listener {
    private final AuditService audit;
    private final Map<Object, Pending> pending = new IdentityHashMap<>();

    public ContainerProcessingAuditListener(Plugin plugin, AuditService audit) {
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCookBefore(BlockCookEvent event) {
        capture(event, event.getBlock(), Actor.environment());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCookAfter(BlockCookEvent event) {
        finish(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBrewBefore(BrewEvent event) {
        capture(event, event.getBlock(), Actor.environment());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBrewAfter(BrewEvent event) {
        finish(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onExtractBefore(FurnaceExtractEvent event) {
        capture(event, event.getBlock(), new de.pixelprotect.model.Actor(event.getPlayer().getUniqueId(), event.getPlayer().getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onExtractAfter(FurnaceExtractEvent event) {
        finish(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDispenseBefore(BlockDispenseEvent event) {
        capture(event, event.getBlock(), Actor.environment());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDispenseAfter(BlockDispenseEvent event) {
        finish(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCrafterBefore(CrafterCraftEvent event) {
        capture(event, event.getBlock(), Actor.environment());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCrafterAfter(CrafterCraftEvent event) {
        finish(event);
    }

    private void capture(Object event, Block block, Actor actor) {
        if (block == null) return;
        try {
            if (!(block.getState() instanceof Container)) return;
            synchronized (pending) {
                pending.put(event, new Pending(UUID.randomUUID(), block, BlockSnapshot.capture(block), actor));
            }
        } catch (RuntimeException ignored) {
            synchronized (pending) {
                pending.remove(event);
            }
        }
    }

    private void finish(Object event) {
        final Pending state;
        synchronized (pending) {
            state = pending.remove(event);
        }
        if (state == null) return;
        try {
            BlockSnapshot after = BlockSnapshot.capture(state.block);
            if (!InventoryDiffService.hasInventoryChanges(
                    deserialize(state.before.inventory()), deserialize(after.inventory()))) return;
            audit.record(state.block, ActionType.CONTAINER, state.actor, state.before, after,
                    state.transactionId, 0L);
        } catch (RuntimeException ignored) {
            // Never allow forensic logging to break vanilla processing.
        }
    }

    private static org.bukkit.inventory.ItemStack[] deserialize(byte[] data) {
        if (data == null || data.length == 0) return new org.bukkit.inventory.ItemStack[0];
        try {
            return org.bukkit.inventory.ItemStack.deserializeItemsFromBytes(data);
        } catch (RuntimeException ignored) {
            return new org.bukkit.inventory.ItemStack[0];
        }
    }

    private record Pending(UUID transactionId, Block block, BlockSnapshot before, Actor actor) {}
}
