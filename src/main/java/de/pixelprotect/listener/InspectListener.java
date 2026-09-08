package de.pixelprotect.listener;

import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.service.InspectService;
import de.pixelprotect.service.MessageService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.plugin.Plugin;

public final class InspectListener implements Listener {
    private final Plugin plugin;
    private final InspectService inspect;

    public InspectListener(Plugin plugin, InspectService inspect) {
        this.plugin = plugin;
        this.inspect = inspect;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        final var player = event.getPlayer();
        if (!inspect.isEnabled(player)) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        final var block = event.getClickedBlock();
        if (block == null) return;

        final int x = block.getX();
        final int y = block.getY();
        final int z = block.getZ();
        final String world = block.getWorld().getName();

        inspect.lookup(player).thenAccept(entries -> player.getScheduler().run(plugin, task -> {
            player.sendPlainMessage("§8§m────────────────────────────────");
            player.sendPlainMessage("§6§lPixelProtect §7– §eBlock-Inspektion");
            player.sendPlainMessage("§7Welt: §f" + world);
            player.sendPlainMessage("§7Koordinaten: §f" + MessageService.coordinates(x, y, z));
            player.sendPlainMessage("§7Block: §f" + block.getType().translationKey());
            player.sendPlainMessage("§8§m────────────────────────────────");

            if (entries.isEmpty()) {
                player.sendPlainMessage("§eKeine gespeicherten Änderungen für diesen Block gefunden.");
                player.sendPlainMessage("§8§m────────────────────────────────");
                return;
            }

            player.sendPlainMessage("§7Gefundene Einträge: §f" + entries.size());
            entries.stream().limit(10).forEach(entry -> sendEntry(player, entry));
            if (entries.size() > 10) {
                player.sendPlainMessage("§8Weitere Einträge sind vorhanden, es werden maximal 10 angezeigt.");
            }
            player.sendPlainMessage("§8§m────────────────────────────────");
        }, null));
    }

    private static void sendEntry(org.bukkit.entity.Player player, AuditEntry entry) {
        final String actor = entry.actorName() == null || entry.actorName().isBlank()
                ? "Unbekannt"
                : entry.actorName();
        player.sendPlainMessage("§e#" + entry.id() + " §7• §f" + actor);
        player.sendPlainMessage("  §7Aktion: §f" + MessageService.action(entry.action()));
        player.sendPlainMessage("  §7Zeit: §f" + MessageService.time(entry.time()));
        player.sendPlainMessage("  §7Koordinaten: §f" + MessageService.coordinates(entry));
    }
}
