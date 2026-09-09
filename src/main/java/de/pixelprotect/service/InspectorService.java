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
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
            .withZone(ZoneId.systemDefault());
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
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Instant since = Instant.now().minus(Duration.ofDays(3650));
                List<DatabaseManager.StoredTransfer> transfers = database.findTransfers(world, x, y, z, 1.5, since, 50);
                List<DatabaseManager.StoredBlock> blocks = database.findBlocks(world, x, y, z, 1.5, since, 50);
                Bukkit.getScheduler().runTask(plugin, () -> send(player, transfers, blocks));
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> sendSingle(player,
                        Component.text("Pixel-Protect: Forensik-Abfrage fehlgeschlagen: " + exception.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    private void send(Player player, List<DatabaseManager.StoredTransfer> transfers,
                      List<DatabaseManager.StoredBlock> blocks) {
        if (!player.isOnline()) return;
        if (transfers.isEmpty() && blocks.isEmpty()) {
            sendSingle(player, Component.text("Keine protokollierten Ereignisse gefunden.", NamedTextColor.YELLOW));
            return;
        }

        Map<UUID, List<DatabaseManager.StoredTransfer>> grouped = new LinkedHashMap<>();
        for (DatabaseManager.StoredTransfer transfer : transfers) {
            grouped.computeIfAbsent(transfer.transactionId(), ignored -> new ArrayList<>()).add(transfer);
        }
        for (List<DatabaseManager.StoredTransfer> group : grouped.values()) sendSingle(player, formatTransferGroup(group));
        for (DatabaseManager.StoredBlock block : blocks) sendSingle(player, formatBlock(block));
    }

    private void sendSingle(Player player, Component content) {
        player.sendMessage(Component.text(SEP + "\n", NamedTextColor.DARK_GRAY)
                .append(content)
                .append(Component.text("\n" + SEP, NamedTextColor.DARK_GRAY)));
    }

    public Component formatTransferGroup(List<DatabaseManager.StoredTransfer> transfers) {
        if (transfers.isEmpty()) return Component.empty();
        DatabaseManager.StoredTransfer first = transfers.getFirst();
        String actor = first.actorName() == null ? "Unbekannt" : first.actorName();
        boolean playerPut = first.source().type() == EndpointType.PLAYER && first.destination().type() != EndpointType.PLAYER;
        boolean playerTake = first.destination().type() == EndpointType.PLAYER && first.source().type() != EndpointType.PLAYER;
        if (playerPut || playerTake) return formatPlayerTransferGroup(actor, transfers, first);
        return formatAutomatedTransferGroup(transfers, first);
    }

    private Component formatPlayerTransferGroup(String actor, List<DatabaseManager.StoredTransfer> transfers,
                                                 DatabaseManager.StoredTransfer first) {
        List<DatabaseManager.StoredTransfer> put = new ArrayList<>();
        List<DatabaseManager.StoredTransfer> take = new ArrayList<>();
        for (DatabaseManager.StoredTransfer transfer : transfers) {
            if (transfer.source().type() == EndpointType.PLAYER && transfer.destination().type() != EndpointType.PLAYER) put.add(transfer);
            else if (transfer.destination().type() == EndpointType.PLAYER && transfer.source().type() != EndpointType.PLAYER) take.add(transfer);
        }

        Component message = Component.text(actor, NamedTextColor.GOLD).append(Component.text(" hat ", NamedTextColor.WHITE));
        if (!put.isEmpty()) {
            message = message.append(formatItems(put, NamedTextColor.GREEN))
                    .append(Component.text(" in " + first.destination().label() + " gelegt", NamedTextColor.WHITE));
        }
        if (!take.isEmpty()) {
            if (!put.isEmpty()) message = message.append(Component.text(",\nsowie ", NamedTextColor.WHITE));
            message = message.append(formatItems(take, NamedTextColor.RED))
                    .append(Component.text(" aus " + firstSourceLabel(take, first) + " entnommen", NamedTextColor.WHITE));
        }
        message = message.append(Component.text(".", NamedTextColor.WHITE));
        return appendTransferMetadata(message, first, containerEndpoint(put, take, first));
    }

    private Component formatAutomatedTransferGroup(List<DatabaseManager.StoredTransfer> transfers,
                                                    DatabaseManager.StoredTransfer first) {
        Component message = Component.text("Automatisch: ", NamedTextColor.YELLOW)
                .append(formatItems(transfers, NamedTextColor.GREEN))
                .append(Component.text(" von " + first.source().label() + " nach " + first.destination().label() + " transportiert.", NamedTextColor.WHITE));
        if (first.attributionName() != null) {
            message = message.append(Component.text(" Transportsystem von ", NamedTextColor.GRAY))
                    .append(Component.text(first.attributionName(), NamedTextColor.GOLD));
        }
        return appendTransferMetadata(message, first, first.destination());
    }

    private Component appendTransferMetadata(Component message, DatabaseManager.StoredTransfer transfer, Endpoint displayEndpoint) {
        return message
                .append(Component.text("\nZeit: ", NamedTextColor.GRAY))
                .append(Component.text(FORMAT.format(transfer.timestamp()), NamedTextColor.WHITE))
                .append(Component.text("\n" + endpointLabel(displayEndpoint) + ": ", NamedTextColor.GRAY))
                .append(Component.text(coordinates(displayEndpoint), NamedTextColor.WHITE))
                .append(Component.text("    Rollback-ID: ", NamedTextColor.GRAY))
                .append(Component.text(rollbackId(transfer.transactionId()), NamedTextColor.YELLOW));
    }

    private Component formatItems(List<DatabaseManager.StoredTransfer> transfers, NamedTextColor color) {
        Component items = Component.empty();
        for (int i = 0; i < transfers.size(); i++) {
            if (i > 0) items = items.append(Component.text(", ", NamedTextColor.WHITE));
            DatabaseManager.StoredTransfer transfer = transfers.get(i);
            items = items.append(Component.text(transfer.amount() + "x ", color)).append(itemName(transfer, color));
        }
        return items;
    }

    private Component itemName(DatabaseManager.StoredTransfer transfer, NamedTextColor color) {
        try {
            var stack = codec.decode(transfer.itemData());
            return Component.translatable(stack.getType().getTranslationKey()).color(color);
        } catch (RuntimeException exception) {
            String key = transfer.itemKey();
            String item = key.contains(":") ? key.substring(key.indexOf(':') + 1).split(":", 2)[0] : key;
            return Component.text(item, color);
        }
    }

    private static String firstSourceLabel(List<DatabaseManager.StoredTransfer> take, DatabaseManager.StoredTransfer first) {
        return take.isEmpty() ? first.source().label() : take.getFirst().source().label();
    }

    private static Endpoint containerEndpoint(List<DatabaseManager.StoredTransfer> put, List<DatabaseManager.StoredTransfer> take,
                                              DatabaseManager.StoredTransfer first) {
        if (!put.isEmpty()) return put.getFirst().destination();
        if (!take.isEmpty()) return take.getFirst().source();
        return first.destination();
    }

    private static String endpointLabel(Endpoint endpoint) {
        if (endpoint.type() == EndpointType.BLOCK_CONTAINER || endpoint.type() == EndpointType.MINECART) return endpoint.label();
        return "Ort";
    }

    private static String coordinates(Endpoint endpoint) {
        if (endpoint.world() == null) return "unbekannt";
        return "X " + endpoint.x() + " Y " + endpoint.y() + " Z " + endpoint.z();
    }

    public static String rollbackId(UUID transactionId) {
        String raw = transactionId.toString().replace("-", "").toUpperCase(Locale.ROOT);
        return "#" + raw.substring(0, 5);
    }

    public Component formatBlock(DatabaseManager.StoredBlock block) {
        return Component.text(block.playerName(), NamedTextColor.GOLD)
                .append(Component.text(" hat Block ", NamedTextColor.WHITE))
                .append(Component.text(block.action().toLowerCase(Locale.GERMANY), NamedTextColor.YELLOW))
                .append(Component.text(" (" + block.blockType() + ") bei X: " + block.x() + " Y: " + block.y() + " Z: " + block.z() + ".", NamedTextColor.WHITE))
                .append(Component.text("\nZeit: ", NamedTextColor.GRAY))
                .append(Component.text(FORMAT.format(block.timestamp()), NamedTextColor.WHITE))
                .append(Component.text("\nRollback-ID: ", NamedTextColor.GRAY))
                .append(Component.text(rollbackId(block.transactionId()), NamedTextColor.YELLOW));
    }
}
