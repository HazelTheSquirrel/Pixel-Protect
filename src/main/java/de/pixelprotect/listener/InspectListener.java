package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.service.AutomationTracker;
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

import java.util.List;

public final class InspectListener implements Listener {
    private static final int MAX_HISTORY_ENTRIES = 10;
    private static final int MAX_DISPLAYED_SLOTS_PER_SNAPSHOT = 54;

    private final Plugin plugin;
    private final InspectService inspect;
    private final AutomationTracker automation;

    public InspectListener(Plugin plugin, InspectService inspect, AutomationTracker automation) {
        this.plugin = plugin;
        this.inspect = inspect;
        this.automation = automation;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        final var player = event.getPlayer();
        if (!player.isOp()) {
            inspect.disable(player);
            return;
        }
        if (!inspect.isEnabled(player)) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        final var block = event.getClickedBlock();
        if (block == null) return;

        final int x = block.getX();
        final int y = block.getY();
        final int z = block.getZ();
        final String world = block.getWorld().getName();
        final var worldId = block.getWorld().getUID();
        final String blockTranslationKey = block.getType().translationKey();

        player.sendActionBar(Component.text("PixelProtect: Block-Historie wird geladen …", NamedTextColor.YELLOW));
        inspect.lookup(worldId, x, y, z).thenAccept(entries -> player.getScheduler().run(plugin, task -> {
            if (!player.isOnline() || !player.isOp() || !inspect.isEnabled(player)) return;
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
                    .append(Component.text("Aktueller Block: ", NamedTextColor.GRAY))
                    .append(Component.text(blockTranslationKey, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Historie: ", NamedTextColor.GRAY))
                    .append(Component.text("letzte 7 Tage", NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("────────────────────────────────", NamedTextColor.DARK_GRAY));

            if (entries.isEmpty()) {
                message = message.append(Component.newline())
                        .append(Component.text("Keine gespeicherten Ereignisse für diesen Block gefunden.", NamedTextColor.YELLOW));
            } else {
                message = message.append(Component.newline())
                        .append(Component.text("Gefundene Ereignisse: ", NamedTextColor.GRAY))
                        .append(Component.text(Integer.toString(Math.min(entries.size(), MAX_HISTORY_ENTRIES)), NamedTextColor.WHITE));
                for (AuditEntry entry : entries.stream().limit(MAX_HISTORY_ENTRIES).toList()) {
                    message = message.append(entryComponent(entry, automation.context(entry.transactionId())));
                }
                if (entries.size() > MAX_HISTORY_ENTRIES) {
                    message = message.append(Component.newline())
                            .append(Component.text("Weitere Ereignisse sind vorhanden; maximal " + MAX_HISTORY_ENTRIES + " werden angezeigt.", NamedTextColor.DARK_GRAY));
                }
            }
            player.sendMessage(message.append(Component.newline()).append(Component.text("────────────────────────────────", NamedTextColor.DARK_GRAY)));
        }, null));
    }

    private static Component entryComponent(AuditEntry entry, AutomationTracker.TransferContext context) {
        final String actor = entry.actorName() == null || entry.actorName().isBlank() ? "Unbekannt" : entry.actorName();
        final String actorId = entry.actor() == null ? "nicht verfügbar" : entry.actor().toString();
        final String before = normalizeState(entry.beforeData());
        final String after = normalizeState(entry.afterData());
        final String source = context == null ? directSource(entry) : context.cause();

        Component result = Component.newline()
                .append(Component.text("#" + entry.id() + " • " + actor, NamedTextColor.YELLOW))
                .append(Component.newline()).append(Component.text("  Aktion: ", NamedTextColor.GRAY)).append(Component.text(MessageService.action(entry.action()), NamedTextColor.WHITE))
                .append(Component.newline()).append(Component.text("  Zeit: ", NamedTextColor.GRAY)).append(Component.text(MessageService.time(entry.time()), NamedTextColor.WHITE))
                .append(Component.newline()).append(Component.text("  Spieler-UUID: ", NamedTextColor.GRAY)).append(Component.text(actorId, NamedTextColor.DARK_GRAY))
                .append(Component.newline()).append(Component.text("  BLOCK VORHER: ", NamedTextColor.GRAY)).append(Component.text(before, NamedTextColor.RED))
                .append(Component.newline()).append(Component.text("  BLOCK NACHHER: ", NamedTextColor.GRAY)).append(Component.text(after, NamedTextColor.GREEN))
                .append(Component.newline()).append(Component.text("  Ursache: ", NamedTextColor.GRAY)).append(Component.text(source, NamedTextColor.YELLOW));

        if (entry.details() != null) {
            result = result.append(Component.newline())
                    .append(Component.text("  Details: ", NamedTextColor.GRAY))
                    .append(Component.text(cleanDetails(entry.details()), NamedTextColor.WHITE));
        }

        result = appendInventorySnapshot(result, "INHALT VORHER", entry.beforeInventory(), NamedTextColor.RED);
        result = appendInventorySnapshot(result, "INHALT NACHHER", entry.afterInventory(), NamedTextColor.GREEN);

        final List<InventoryDiffService.ItemChange> changes = InventoryDiffService.itemChanges(entry.beforeInventory(), entry.afterInventory());
        if (!changes.isEmpty()) {
            result = result.append(Component.newline()).append(Component.text("  ÄNDERUNGEN IM INHALT:", NamedTextColor.GOLD));
            for (InventoryDiffService.ItemChange change : changes) {
                String prefix = change.amount() > 0 ? "+ " : "− ";
                result = result.append(Component.newline())
                        .append(Component.text("    " + prefix + Math.abs(change.amount()) + " × ", change.amount() > 0 ? NamedTextColor.GREEN : NamedTextColor.RED))
                        .append(change.displayName());
            }
        }

        if (context != null) {
            if (context.owner() != null) {
                result = result.append(Component.newline()).append(Component.text("  Indirekt verursacht durch: ", NamedTextColor.GRAY)).append(Component.text(context.owner().name(), NamedTextColor.WHITE));
            }
            result = appendLocation(result, "Quelle", context.source());
            result = appendLocation(result, "Ziel", context.destination());
            if (context.mechanism() != null) {
                result = result.append(Component.newline()).append(Component.text("  Mechanismus: ", NamedTextColor.GRAY))
                        .append(Component.text(context.mechanismType().translationKey(), NamedTextColor.WHITE))
                        .append(Component.text(" @ ", NamedTextColor.GRAY))
                        .append(Component.text(location(context.mechanism()), NamedTextColor.WHITE));
            }
        }
        if (entry.transactionId() != null) {
            result = result.append(Component.newline()).append(Component.text("  Transaktion: ", NamedTextColor.GRAY)).append(Component.text(entry.transactionId().toString(), NamedTextColor.DARK_GRAY));
        }
        return result;
    }

    private static Component appendInventorySnapshot(Component base, String label, byte[] encoded, NamedTextColor color) {
        if (encoded == null || encoded.length == 0) return base;
        List<InventoryDiffService.SlotItem> items = InventoryDiffService.occupiedSlots(encoded);
        Component result = base.append(Component.newline()).append(Component.text("  " + label + ":", color));
        if (items.isEmpty()) {
            return result.append(Component.text(" leer", NamedTextColor.DARK_GRAY));
        }
        int displayed = 0;
        for (InventoryDiffService.SlotItem item : items) {
            if (displayed++ >= MAX_DISPLAYED_SLOTS_PER_SNAPSHOT) {
                result = result.append(Component.newline()).append(Component.text("    … weitere Slots vorhanden", NamedTextColor.DARK_GRAY));
                break;
            }
            result = result.append(Component.newline())
                    .append(Component.text("    Slot " + item.slot() + ": ", NamedTextColor.GRAY))
                    .append(Component.text(item.amount() + " × ", NamedTextColor.WHITE))
                    .append(item.displayName());
        }
        return result;
    }

    private static String directSource(AuditEntry entry) {
        return switch (entry.action()) {
            case BREAK, PLACE, BUCKET, SIGN, COMMAND, CHAT, INTERACT, ENTITY_INTERACT, CRAFT, TRADE -> "Direkt durch Spieleraktion";
            case EXPLOSION, TNT_PRIME -> "Indirekt durch Explosion/Explosionsquelle";
            case PISTON, DISPENSE, COMPOST, CAULDRON -> "Indirekt durch Mechanik";
            case FLUID -> "Indirekt durch Flüssigkeitsfluss";
            case GROW, FORM, SPREAD, DECAY, MOISTURE, SCULK -> "Indirekt durch Weltmechanik";
            case ENTITY_CHANGE, ENTITY_SPAWN, ENTITY_DEATH, ENTITY_REMOVE, ENTITY_DAMAGE, PROJECTILE -> "Indirekt durch Entität";
            case WORLD_EDIT -> "Durch WorldEdit-Aktion";
            default -> "Server-/Weltmechanik";
        };
    }

    private static String normalizeState(String value) {
        return value == null || value.isBlank() ? "minecraft:air" : value;
    }

    private static String cleanDetails(String value) {
        return value.replace('\u0000', ' ').replace('\n', ' ').trim();
    }

    private static Component appendLocation(Component base, String label, AutomationTracker.LocationData location) {
        return location == null ? base : base.append(Component.newline()).append(Component.text("  " + label + ": ", NamedTextColor.GRAY)).append(Component.text(location(location), NamedTextColor.WHITE));
    }

    private static String location(AutomationTracker.LocationData location) {
        return MessageService.coordinates(location.x(), location.y(), location.z());
    }
}
