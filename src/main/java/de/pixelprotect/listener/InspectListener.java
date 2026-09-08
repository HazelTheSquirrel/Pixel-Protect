package de.pixelprotect.listener;

import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.service.InspectService;
import de.pixelprotect.service.InventoryDiffService;
import de.pixelprotect.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
        final var worldId = block.getWorld().getUID();

        inspect.lookup(worldId, x, y, z).thenAccept(entries -> player.getScheduler().run(plugin, task -> {
            if (!player.isOnline()) return;
            Component message = Component.empty()
                    .append(Component.text("────────────────────────────────", NamedTextColor.DARK_GRAY))
                    .append(Component.newline())
                    .append(Component.text("PixelProtect", NamedTextColor.GOLD))
                    .append(Component.text(" – ", NamedTextColor.GRAY))
                    .append(Component.text("Block-Inspektion", NamedTextColor.YELLOW))
                    .append(Component.newline())
                    .append(Component.text("Welt: ", NamedTextColor.GRAY))
                    .append(Component.text(world, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Koordinaten: ", NamedTextColor.GRAY))
                    .append(Component.text(MessageService.coordinates(x, y, z), NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Block: ", NamedTextColor.GRAY))
                    .append(Component.text(block.getType().translationKey(), NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("────────────────────────────────", NamedTextColor.DARK_GRAY));

            if (entries.isEmpty()) {
                message = message.append(Component.newline())
                        .append(Component.text("Keine gespeicherten Änderungen für diesen Block gefunden.", NamedTextColor.YELLOW));
            } else {
                message = message.append(Component.newline())
                        .append(Component.text("Gefundene Einträge: ", NamedTextColor.GRAY))
                        .append(Component.text(Integer.toString(Math.min(entries.size(), 10)), NamedTextColor.WHITE));
                for (AuditEntry entry : entries.stream().limit(10).toList()) {
                    message = message.append(entryComponent(entry));
                }
                if (entries.size() > 10) {
                    message = message.append(Component.newline())
                            .append(Component.text("Weitere Einträge sind vorhanden; maximal 10 werden angezeigt.", NamedTextColor.DARK_GRAY));
                }
            }

            message = message.append(Component.newline())
                    .append(Component.text("────────────────────────────────", NamedTextColor.DARK_GRAY));
            player.sendMessage(message);
        }, null));
    }

    private static Component entryComponent(AuditEntry entry) {
        final String actor = entry.actorName() == null || entry.actorName().isBlank()
                ? "Unbekannt"
                : entry.actorName();
        Component result = Component.newline()
                .append(Component.text("#" + entry.id(), NamedTextColor.YELLOW))
                .append(Component.text(" • ", NamedTextColor.GRAY))
                .append(Component.text(actor, NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("  Aktion: ", NamedTextColor.GRAY))
                .append(Component.text(MessageService.action(entry.action()), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("  Zeit: ", NamedTextColor.GRAY))
                .append(Component.text(MessageService.time(entry.time()), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("  Koordinaten: ", NamedTextColor.GRAY))
                .append(Component.text(MessageService.coordinates(entry), NamedTextColor.WHITE));

        final var changes = InventoryDiffService.itemChanges(entry.beforeInventory(), entry.afterInventory());
        if (!changes.isEmpty()) {
            result = result.append(Component.newline())
                    .append(Component.text("  Änderungen:", NamedTextColor.GRAY));
            for (InventoryDiffService.ItemChange change : changes) {
                final String prefix = change.amount() > 0 ? "+ " : "− ";
                result = result.append(Component.newline())
                        .append(Component.text("    " + prefix + Math.abs(change.amount()) + " × ",
                                change.amount() > 0 ? NamedTextColor.GREEN : NamedTextColor.RED))
                        .append(change.displayName());
            }
        }
        return result;
    }
}
