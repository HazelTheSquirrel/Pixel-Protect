package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import de.pixelprotect.util.ItemCodec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class InspectorService {
    private static final String SEP = "________________________________________________________________________________";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).withZone(ZoneId.systemDefault());
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final ItemCodec codec;
    private final java.util.Set<UUID> inspectors = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public InspectorService(JavaPlugin plugin, DatabaseManager database, ItemCodec codec) {
        this.plugin = plugin;
        this.database = database;
        this.codec = codec;
    }

    public boolean toggle(Player player) {
        if (inspectors.remove(player.getUniqueId())) return false;
        inspectors.add(player.getUniqueId());
        return true;
    }

    public boolean isInspector(Player player) {
        return inspectors.contains(player.getUniqueId());
    }

    public void interact(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isInspector(player) || event.getAction() == Action.PHYSICAL) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        event.setCancelled(true);
        String world = block.getWorld().getName();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Instant since = Instant.now().minus(Duration.ofDays(3650));
                List<DatabaseManager.StoredTransfer> transfers = database.findTransfers(world, x, y, z, 1.5, since, 50);
                List<DatabaseManager.StoredBlock> blocks = database.findBlocks(world, x, y, z, 1.5, since, 50);
                Bukkit.getScheduler().runTask(plugin, () -> send(player, transfers, blocks, world, x, y, z));
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Component.text("Pixel-Protect: Forensik-Abfrage fehlgeschlagen: " + exception.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    private void send(Player player, List<DatabaseManager.StoredTransfer> transfers, List<DatabaseManager.StoredBlock> blocks, String world, int x, int y, int z) {
        if (!player.isOnline()) return;

        player.sendMessage(Component.text(SEP, NamedTextColor.DARK_GRAY));
        player.sendMessage(Component.text("Pixel-Protect • " + world + " • X: " + x + " Y: " + y + " Z: " + z, NamedTextColor.GRAY));

        if (transfers.isEmpty() && blocks.isEmpty()) {
            player.sendMessage(Component.text("Keine protokollierten Ereignisse gefunden.", NamedTextColor.YELLOW));
            player.sendMessage(Component.text(SEP, NamedTextColor.DARK_GRAY));
            return;
        }

        Map<UUID, List<DatabaseManager.StoredTransfer>> grouped = new LinkedHashMap<>();
        for (DatabaseManager.StoredTransfer transfer : transfers) {
            grouped.computeIfAbsent(transfer.transactionId(), ignored -> new ArrayList<>()).add(transfer);
        }
        for (List<DatabaseManager.StoredTransfer> group : grouped.values()) {
            player.sendMessage(formatTransferGroup(group));
            player.sendMessage(Component.text(SEP, NamedTextColor.DARK_GRAY));
        }

        for (DatabaseManager.StoredBlock block : blocks) {
            player.sendMessage(formatBlock(block));
            player.sendMessage(Component.text(SEP, NamedTextColor.DARK_GRAY));
        }
    }

    public Component formatTransferGroup(List<DatabaseManager.StoredTransfer> transfers) {
        if (transfers.isEmpty()) return Component.empty();
        DatabaseManager.StoredTransfer first = transfers.getFirst();
        String actor = first.actorName() == null ? "Unbekannt" : first.actorName();
        boolean playerPut = first.source().type() == EndpointType.PLAYER && first.destination().type() != EndpointType.PLAYER;
        boolean playerTake = first.destination().type() == EndpointType.PLAYER && first.source().type() != EndpointType.PLAYER;

        Component items = Component.empty();
        for (int i = 0; i < transfers.size(); i++) {
            if (i > 0) items = items.append(Component.text(", ", NamedTextColor.GRAY));
            DatabaseManager.StoredTransfer transfer = transfers.get(i);
            items = items.append(Component.text(transfer.amount() + "x ", playerTake ? NamedTextColor.RED : NamedTextColor.GREEN))
                    .append(itemName(transfer));
        }

        Component message;
        if (playerPut) {
            message = Component.text(actor, NamedTextColor.GOLD)
                    .append(Component.text(" hat ", NamedTextColor.WHITE))
                    .append(items)
                    .append(Component.text(" in " + first.destination().label() + " gelegt.", NamedTextColor.WHITE));
        } else if (playerTake) {
            message = Component.text(actor, NamedTextColor.GOLD)
                    .append(Component.text(" hat ", NamedTextColor.WHITE))
                    .append(items)
                    .append(Component.text(" aus " + first.source().label() + " entnommen.", NamedTextColor.WHITE));
        } else {
            message = Component.text("Automatisch: ", NamedTextColor.YELLOW)
                    .append(items)
                    .append(Component.text(" von " + first.source().label() + " nach " + first.destination().label() + " transportiert.", NamedTextColor.WHITE));
            if (first.attributionName() != null) {
                message = message.append(Component.text(" Transportsystem von ", NamedTextColor.GRAY))
                        .append(Component.text(first.attributionName(), NamedTextColor.GOLD));
            }
        }

        return message
                .append(Component.text("\nZeit: ", NamedTextColor.GRAY))
                .append(Component.text(FORMAT.format(first.timestamp()), NamedTextColor.WHITE))
                .append(Component.text("\nQuelle: ", NamedTextColor.GRAY))
                .append(Component.text(endpoint(first.source()), NamedTextColor.WHITE))
                .append(Component.text("\nZiel: ", NamedTextColor.GRAY))
                .append(Component.text(endpoint(first.destination()), NamedTextColor.WHITE))
                .append(Component.text("\nRollback-ID: ", NamedTextColor.GRAY))
                .append(Component.text(first.transactionId().toString(), NamedTextColor.YELLOW));
    }

    private Component itemName(DatabaseManager.StoredTransfer transfer) {
        try {
            var stack = codec.decode(transfer.itemData());
            return Component.translatable(stack.getType().getTranslationKey()).color(transferDirectionColor(transfer));
        } catch (RuntimeException exception) {
            String key = transfer.itemKey();
            String item = key.contains(":") ? key.substring(key.indexOf(':') + 1).split(":", 2)[0] : key;
            return Component.text(item, transferDirectionColor(transfer));
        }
    }

    private NamedTextColor transferDirectionColor(DatabaseManager.StoredTransfer transfer) {
        if (transfer.destination().type() == EndpointType.PLAYER) return NamedTextColor.RED;
        if (transfer.source().type() == EndpointType.PLAYER) return NamedTextColor.GREEN;
        return NamedTextColor.GREEN;
    }

    public Component formatBlock(DatabaseManager.StoredBlock block) {
        return Component.text(block.playerName(), NamedTextColor.GOLD)
                .append(Component.text(" hat Block ", NamedTextColor.WHITE))
                .append(Component.text(block.action().toLowerCase(Locale.GERMANY), NamedTextColor.YELLOW))
                .append(Component.text(" (" + block.blockType() + ") bei X: " + block.x() + " Y: " + block.y() + " Z: " + block.z(), NamedTextColor.WHITE))
                .append(Component.text("\nZeit: ", NamedTextColor.GRAY))
                .append(Component.text(FORMAT.format(block.timestamp()), NamedTextColor.WHITE))
                .append(Component.text("\nRollback-ID: ", NamedTextColor.GRAY))
                .append(Component.text(block.transactionId().toString(), NamedTextColor.YELLOW));
    }

    private static String endpoint(Endpoint endpoint) {
        if (endpoint.world() == null) return endpoint.label();
        return endpoint.label() + " @ X: " + endpoint.x() + " Y: " + endpoint.y() + " Z: " + endpoint.z();
    }
}
