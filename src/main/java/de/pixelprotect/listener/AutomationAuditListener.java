package de.pixelprotect.listener;

import de.pixelprotect.service.AutomationTracker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

/** Captures the only reliable player-to-automation link: who placed or removed the mechanism. */
public final class AutomationAuditListener implements Listener {
    private final AutomationTracker tracker;

    public AutomationAuditListener(Plugin plugin, AutomationTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        tracker.recordPlacement(event.getBlockPlaced(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        tracker.recordRemoval(event.getBlock());
    }
}
