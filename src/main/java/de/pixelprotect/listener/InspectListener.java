package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.service.InspectService;
import de.pixelprotect.service.InventoryDiffService;
import de.pixelprotect.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event.Result;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Simple, safe inspector: one compact result per right-clicked block. */
public final class InspectListener implements Listener {
    private static final int MAX_PLAYERS = 8;
    private static final int MAX_ITEMS_PER_LINE = 4;

    private final Plugin plugin;
    private final InspectService inspect;

    public InspectListener(Plugin plugin, InspectService inspect, de.pixelprotect.service.AutomationTracker automation) {
        this.plugin = plugin;
        this.inspect = inspect;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        final var player = event.getPlayer();
        if (!player.hasPermission("pixelprotect.inspect")) {
            inspect.disable(player);
            return;
        }
        if (!inspect.isEnabled(player) || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        final var block = event.getClickedBlock();
        if (block == null) return;

        // Inspector mode must never accidentally open a chest, press a button, place a block, etc.
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);

        final int x = block.getX();
        final int y = block.getY();
        final int z = block.getZ();
        final String currentBlock = block.getType().translationKey();

        inspect.lookup(block.getWorld().getUID(), x, y, z).thenAccept(entries -> player.getScheduler().run(plugin, task -> {
            if (!player.isOnline() || !player.hasPermission("pixelprotect.inspect") || !inspect.isEnabled(player)) return;
            player.sendMessage(buildMessage(x, y, z, currentBlock, entries));
        }, null));
    }

    private static Component buildMessage(int x, int y, int z, String currentBlock, List<AuditEntry> entries) {
        Component message = Component.text("PixelProtect", NamedTextColor.GOLD)
                .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                .append(Component.text(MessageService.coordinates(x, y, z), NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("Block: ", NamedTextColor.GRAY))
                .append(Component.text(humanBlock(currentBlock), NamedTextColor.WHITE));

        if (entries.isEmpty()) {
            return message.append(Component.newline())
                    .append(Component.text("Keine Änderungen gefunden.", NamedTextColor.YELLOW));
        }

        List<AuditEntry> blockChanges = entries.stream().filter(InspectListener::isBlockChange).toList();
        List<AuditEntry> inventoryChanges = entries.stream().filter(InspectListener::isInventoryChange).toList();

        if (!blockChanges.isEmpty()) message = appendBlockHistory(message, blockChanges);
        if (!inventoryChanges.isEmpty()) message = appendInventoryHistory(message, inventoryChanges);

        if (blockChanges.isEmpty() && inventoryChanges.isEmpty()) {
            AuditEntry latest = entries.getFirst();
            message = message.append(Component.newline())
                    .append(Component.text("Letzte Änderung: ", NamedTextColor.GRAY))
                    .append(Component.text(actionText(latest.action()), NamedTextColor.WHITE))
                    .append(Component.text(" von ", NamedTextColor.GRAY))
                    .append(Component.text(actor(latest), NamedTextColor.YELLOW))
                    .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(MessageService.time(latest.time()), NamedTextColor.GRAY));
        }
        return message;
    }

    private static Component appendBlockHistory(Component base, List<AuditEntry> entries) {
        AuditEntry latestBreak = entries.stream().filter(InspectListener::isBreak).findFirst().orElse(null);
        AuditEntry latestPlace = entries.stream().filter(InspectListener::isPlace).findFirst().orElse(null);
        AuditEntry latest = entries.getFirst();

        Component result = base.append(Component.newline());
        if (latest.action() == ActionType.PLACE && latestPlace != null) {
            result = result.append(Component.text("Platziert von: ", NamedTextColor.GRAY))
                    .append(Component.text(actor(latestPlace), NamedTextColor.GREEN))
                    .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(MessageService.time(latestPlace.time()), NamedTextColor.GRAY));
        } else if (latest.action() == ActionType.BREAK || latest.action() == ActionType.BLOCK_BREAK) {
            AuditEntry breaker = latestBreak == null ? latest : latestBreak;
            result = result.append(Component.text("Abgebaut von: ", NamedTextColor.GRAY))
                    .append(Component.text(actor(breaker), NamedTextColor.RED))
                    .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(MessageService.time(breaker.time()), NamedTextColor.GRAY));
        } else {
            if (latestPlace != null) {
                result = result.append(Component.text("Platziert von: ", NamedTextColor.GRAY))
                        .append(Component.text(actor(latestPlace), NamedTextColor.GREEN))
                        .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(MessageService.time(latestPlace.time()), NamedTextColor.GRAY));
            }
            if (latestBreak != null) {
                result = result.append(Component.newline())
                        .append(Component.text("Abgebaut von: ", NamedTextColor.GRAY))
                        .append(Component.text(actor(latestBreak), NamedTextColor.RED))
                        .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(MessageService.time(latestBreak.time()), NamedTextColor.GRAY));
            }
        }
        return result;
    }

    private static Component appendInventoryHistory(Component base, List<AuditEntry> entries) {
        Map<String, PersonChanges> byPlayer = new LinkedHashMap<>();
        for (AuditEntry entry : entries) {
            List<InventoryDiffService.ItemChange> changes = InventoryDiffService.itemChanges(entry.beforeInventory(), entry.afterInventory());
            if (changes.isEmpty()) continue;
            PersonChanges person = byPlayer.computeIfAbsent(actor(entry), ignored -> new PersonChanges());
            person.time = Math.max(person.time, entry.time());
            for (InventoryDiffService.ItemChange change : changes) {
                if (change.added()) person.added.add(change);
                if (change.removed()) person.removed.add(change);
            }
        }
        if (byPlayer.isEmpty()) return base;

        Component result = base.append(Component.newline())
                .append(Component.text("Inhalt geändert:", NamedTextColor.GOLD));
        int players = 0;
        for (Map.Entry<String, PersonChanges> entry : byPlayer.entrySet()) {
            if (players++ >= MAX_PLAYERS) break;
            PersonChanges changes = entry.getValue();
            if (!changes.removed.isEmpty()) {
                result = result.append(Component.newline())
                        .append(Component.text("Rausgenommen von ", NamedTextColor.GRAY))
                        .append(Component.text(entry.getKey(), NamedTextColor.RED))
                        .append(Component.text(": ", NamedTextColor.GRAY))
                        .append(itemSummary(changes.removed));
            }
            if (!changes.added.isEmpty()) {
                result = result.append(Component.newline())
                        .append(Component.text("Reingelegt von ", NamedTextColor.GRAY))
                        .append(Component.text(entry.getKey(), NamedTextColor.GREEN))
                        .append(Component.text(": ", NamedTextColor.GRAY))
                        .append(itemSummary(changes.added));
            }
        }
        if (byPlayer.size() > MAX_PLAYERS) {
            result = result.append(Component.newline())
                    .append(Component.text("Weitere Änderungen vorhanden.", NamedTextColor.DARK_GRAY));
        }
        return result;
    }

    private static Component itemSummary(List<InventoryDiffService.ItemChange> changes) {
        Component result = Component.empty();
        int shown = 0;
        for (InventoryDiffService.ItemChange change : changes) {
            if (shown++ > 0) result = result.append(Component.text(", ", NamedTextColor.DARK_GRAY));
            result = result.append(Component.text(Math.abs(change.amount()) + "× ", NamedTextColor.WHITE))
                    .append(change.displayName());
            if (shown >= MAX_ITEMS_PER_LINE) {
                if (changes.size() > shown) result = result.append(Component.text(" …", NamedTextColor.DARK_GRAY));
                break;
            }
        }
        return result;
    }

    private static boolean isBlockChange(AuditEntry entry) {
        return isBreak(entry) || isPlace(entry);
    }

    private static boolean isBreak(AuditEntry entry) {
        return entry.action() == ActionType.BREAK || entry.action() == ActionType.BLOCK_BREAK;
    }

    private static boolean isPlace(AuditEntry entry) {
        return entry.action() == ActionType.PLACE;
    }

    private static boolean isInventoryChange(AuditEntry entry) {
        return entry.action() == ActionType.CONTAINER || entry.action() == ActionType.INVENTORY ||
                entry.action() == ActionType.CRAFT || entry.action() == ActionType.TRADE;
    }

    private static String actor(AuditEntry entry) {
        return entry.actorName() == null || entry.actorName().isBlank() ? "Unbekannt" : entry.actorName();
    }

    private static String actionText(ActionType action) {
        return switch (action) {
            case BREAK, BLOCK_BREAK -> "Abgebaut";
            case PLACE -> "Platziert";
            case CONTAINER, INVENTORY -> "Inventar geändert";
            case CRAFT -> "Gecraftet";
            case TRADE -> "Gehandelt";
            default -> "Interaktion";
        };
    }

    private static String humanBlock(String translationKey) {
        if (translationKey == null || translationKey.isBlank()) return "Unbekannter Block";
        String value = translationKey.startsWith("minecraft:") ? translationKey.substring("minecraft:".length()) : translationKey;
        return value.replace('_', ' ');
    }

    private static final class PersonChanges {
        private long time;
        private final List<InventoryDiffService.ItemChange> added = new ArrayList<>();
        private final List<InventoryDiffService.ItemChange> removed = new ArrayList<>();
    }
}
