package de.pixelprotect.listener;

import de.pixelprotect.service.InspectService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public final class InspectListener implements Listener {
    private final InspectService inspect;

    public InspectListener(InspectService inspect) {
        this.inspect = inspect;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!inspect.isEnabled(event.getPlayer())) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        inspect.lookup(event.getPlayer()).thenAccept(entries -> {
            if (entries.isEmpty()) {
                event.getPlayer().sendPlainMessage("PixelProtect: no history for the targeted block.");
                return;
            }
            event.getPlayer().sendPlainMessage("PixelProtect: " + entries.size() + " history record(s) for the targeted block:");
            entries.stream().limit(10).forEach(entry -> event.getPlayer().sendPlainMessage(
                    "#" + entry.id() + " " + entry.actorName() + " " + entry.action() + " @ " + entry.time()));
        });
    }
}
