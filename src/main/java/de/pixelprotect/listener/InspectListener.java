package de.pixelprotect.listener;

import de.pixelprotect.service.InspectService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

public final class InspectListener implements Listener {
    private final Plugin plugin;
    private final InspectService inspect;

    public InspectListener(Plugin plugin, InspectService inspect) {
        this.plugin = plugin;
        this.inspect = inspect;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        final var player = event.getPlayer();
        if (!inspect.isEnabled(player)) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        inspect.lookup(player).thenAccept(entries -> player.getScheduler().run(plugin, task -> {
            if (entries.isEmpty()) {
                player.sendPlainMessage("PixelProtect: no history for the targeted block.");
                return;
            }
            player.sendPlainMessage("PixelProtect: " + entries.size() + " history record(s) for the targeted block:");
            entries.stream().limit(10).forEach(entry -> player.sendPlainMessage(
                    "#" + entry.id() + " " + entry.actorName() + " " + entry.action() + " @ " + entry.time()));
        }, null));
    }
}
