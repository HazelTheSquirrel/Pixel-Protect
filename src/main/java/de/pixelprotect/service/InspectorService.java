package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import de.pixelprotect.model.TransferLog;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class InspectorService {
    private static final String SEPARATOR = "________________________________________________________________";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final DatabaseManager database;
    private final int limit;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    public InspectorService(org.bukkit.plugin.java.JavaPlugin plugin, DatabaseManager database, int limit) {
        this.plugin = plugin;
        this.database = database;
        this.limit = Math.max(1, limit);
    }

    public boolean toggle(Player player) {
        if (enabled.remove(player.getUniqueId())) return false;
        enabled.add(player.getUniqueId());
        return true;
    }

    public boolean isEnabled(Player player) {
        return enabled.contains(player.getUniqueId());
    }

    public void disable(Player player) {
        enabled.remove(player.getUniqueId());
    }

    public void inspect(Player player, Block block) {
        if (!isEnabled(player)) return;
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        player.sendMessage(Component.text("Pixel-Protect: Datenbankabfrage läuft …", NamedTextColor.GRAY));
        CompletableFuture
                .supplyAsync(() -> lookup(world, x, y, z))
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> send(player, result)));
    }

    private Result lookup(String world, int x, int y, int z) {
        try {
            Instant since = Instant.now().minus(Duration.ofDays(30));
            List<DatabaseManager.StoredBlock> blocks = database.inspectBlocks(world, x, y, z, 1.5, since, limit);
            List<DatabaseManager.StoredTransfer> transfers = database.inspectTransfers(world, x, y, z, 1.5, since, limit);
            return new Result(blocks, transfers, null);
        } catch (Exception exception) {
            return new Result(List.of(), List.of(), exception);
        }
    }

    private void send(Player player, Result result) {
        if (!player.isOnline()) return;
        if (result.error() != null) {
            player.sendMessage(Component.text("Pixel-Protect: Lookup fehlgeschlagen: " + result.error().getMessage(), NamedTextColor.RED));
            return;
        }
        if (result.blocks().isEmpty() && result.transfers().isEmpty()) {
            player.sendMessage(Component.text("Pixel-Protect: Keine Forensik-Einträge gefunden.", NamedTextColor.YELLOW));
            return;
        }

        Map<UUID, Tx> grouped = new LinkedHashMap<>();
        for (DatabaseManager.StoredBlock block : result.blocks()) {
            grouped.computeIfAbsent(block.log().transactionId(), Tx::new).blocks().add(block);
        }
        for (DatabaseManager.StoredTransfer transfer : result.transfers()) {
            grouped.computeIfAbsent(transfer.log().transactionId(), Tx::new).transfers().add(transfer);
        }

        grouped.values().stream()
                .sorted(Comparator.comparing(Tx::timestamp).reversed())
                .limit(limit)
                .forEach(tx -> sendTransaction(player, tx));
    }

    private void sendTransaction(Player player, Tx tx) {
        if (!tx.transfers().isEmpty()) {
            sendTransferTransaction(player, tx);
            return;
        }
        for (DatabaseManager.StoredBlock stored : tx.blocks()) {
            sendBlockTransaction(player, stored.log());
        }
    }

    private void sendTransferTransaction(Player player, Tx tx) {
        List<DatabaseManager.StoredTransfer> deposits = new ArrayList<>();
        List<DatabaseManager.StoredTransfer> withdrawals = new ArrayList<>();
        List<DatabaseManager.StoredTransfer> other = new ArrayList<>();

        for (DatabaseManager.StoredTransfer stored : tx.transfers()) {
            TransferLog log = stored.log();
            if (isPlayerToEndpoint(log)) deposits.add(stored);
            else if (isEndpointToPlayer(log)) withdrawals.add(stored);
            else other.add(stored);
        }

        String actor = tx.actorName();
        Endpoint container = tx.primaryEndpoint();
        String endpointLabel = endpointLabel(container);
        StringBuilder firstLine = new StringBuilder(actor).append(" hat ");
        boolean wroteDirection = false;

        if (!deposits.isEmpty()) {
            firstLine.append(formatItems(deposits)).append(" in ").append(endpointLabel).append(" gelegt");
            wroteDirection = true;
        }

        if (!withdrawals.isEmpty()) {
            if (wroteDirection) {
                firstLine.append(",");
                player.sendMessage(Component.text(firstLine.toString(), NamedTextColor.WHITE));
                player.sendMessage(Component.text("sowie " + formatItems(withdrawals) + " aus " + endpointLabel + " entnommen.", NamedTextColor.WHITE));
            } else {
                firstLine.append(formatItems(withdrawals)).append(" aus ").append(endpointLabel).append(" entnommen.");
                player.sendMessage(Component.text(firstLine.toString(), NamedTextColor.WHITE));
            }
        } else if (wroteDirection) {
            firstLine.append(".");
            player.sendMessage(Component.text(firstLine.toString(), NamedTextColor.WHITE));
        }

        if (!other.isEmpty()) {
            for (DatabaseManager.StoredTransfer stored : other) {
                TransferLog log = stored.log();
                player.sendMessage(Component.text(actor + " hat " + stored.log().amount() + "x " + itemName(log.itemKey()) + " von " + endpointLabel(log.source()) + " nach " + endpointLabel(log.destination()) + " bewegt.", NamedTextColor.WHITE));
            }
        }

        player.sendMessage(Component.text("", NamedTextColor.WHITE));
        player.sendMessage(Component.text("Zeit: " + TIME_FORMAT.format(tx.timestamp()), NamedTextColor.WHITE));
        player.sendMessage(Component.text(containerLine(container, endpointLabel) + "    Rollback-ID: " + rollbackId(tx.id()), NamedTextColor.WHITE));
        player.sendMessage(Component.text(SEPARATOR, NamedTextColor.WHITE));
    }

    private void sendBlockTransaction(Player player, BlockLog log) {
        player.sendMessage(Component.text(SEPARATOR, NamedTextColor.WHITE));
        String verb = log.action().equals("BREAK") ? "abgebaut" : "platziert";
        player.sendMessage(Component.text(log.playerName() + " hat " + itemName(log.blockType()) + " " + verb + ".", NamedTextColor.WHITE));
        player.sendMessage(Component.text("", NamedTextColor.WHITE));
        player.sendMessage(Component.text("Zeit: " + TIME_FORMAT.format(log.timestamp()), NamedTextColor.WHITE));
        player.sendMessage(Component.text("Block: X " + log.x() + " Y " + log.y() + " Z " + log.z() + "    Rollback-ID: " + rollbackId(log.transactionId()), NamedTextColor.WHITE));
        player.sendMessage(Component.text(SEPARATOR, NamedTextColor.WHITE));
    }

    private static String formatItems(List<DatabaseManager.StoredTransfer> entries) {
        Map<String, Integer> amounts = new LinkedHashMap<>();
        for (DatabaseManager.StoredTransfer entry : entries) {
            TransferLog log = entry.log();
            amounts.merge(itemName(log.itemKey()), log.amount(), Integer::sum);
        }
        return amounts.entrySet().stream()
                .map(entry -> entry.getValue() + "x " + entry.getKey())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private static boolean isPlayerToEndpoint(TransferLog log) {
        return log.source().type() == EndpointType.PLAYER && log.destination().type() != EndpointType.PLAYER;
    }

    private static boolean isEndpointToPlayer(TransferLog log) {
        return log.destination().type() == EndpointType.PLAYER && log.source().type() != EndpointType.PLAYER;
    }

    private static String containerLine(Endpoint endpoint, String label) {
        if (endpoint == null || endpoint.world() == null) return label + ": nicht verfügbar";
        return label + ": X " + endpoint.x() + " Y " + endpoint.y() + " Z " + endpoint.z();
    }

    private static String endpointLabel(Endpoint endpoint) {
        if (endpoint == null) return "Unbekannt";
        return switch (endpoint.type()) {
            case CONTAINER -> "Kiste";
            case HOPPER -> "Trichter";
            case MINECART -> endpoint.label() != null && endpoint.label().contains("hopper") ? "Trichter-Lore" : "Lore";
            case GROUND -> "Boden";
            case PLAYER -> "Inventar";
            default -> endpoint.label() == null ? "Unbekannt" : endpoint.label();
        };
    }

    private static String itemName(String raw) {
        String key = raw == null ? "unknown" : raw.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return switch (key) {
            case "oak_log", "oak_wood", "stripped_oak_log", "stripped_oak_wood" -> "Eichenholz";
            case "stone" -> "Stein";
            case "white_wool" -> "Wolle";
            case "iron_ingot" -> "Eisen";
            case "gold_ingot" -> "Gold";
            case "diamond" -> "Diamant";
            case "emerald" -> "Smaragd";
            case "coal" -> "Kohle";
            case "copper_ingot" -> "Kupfer";
            case "redstone" -> "Redstone";
            case "lapis_lazuli" -> "Lapislazuli";
            case "quartz" -> "Quarz";
            case "dirt" -> "Erde";
            case "grass_block" -> "Grasblock";
            case "sand" -> "Sand";
            case "gravel" -> "Kies";
            case "cobblestone" -> "Bruchstein";
            case "netherrack" -> "Netherrack";
            case "end_stone" -> "Endstein";
            case "obsidian" -> "Obsidian";
            case "glass" -> "Glas";
            case "chest" -> "Kiste";
            case "hopper" -> "Trichter";
            case "barrel" -> "Fass";
            case "shulker_box" -> "Shulkerkiste";
            case "ender_chest" -> "Endertruhe";
            default -> prettify(key);
        };
    }

    private static String prettify(String key) {
        String[] words = key.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.isEmpty() ? "Unbekannt" : result.toString();
    }

    public static String rollbackId(UUID tx) {
        return "#" + tx.toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
    }

    private static final class Tx {
        private final UUID id;
        private final List<DatabaseManager.StoredTransfer> transfers = new ArrayList<>();
        private final List<DatabaseManager.StoredBlock> blocks = new ArrayList<>();

        private Tx(UUID id) {
            this.id = id;
        }

        private UUID id() {
            return id;
        }

        private List<DatabaseManager.StoredTransfer> transfers() {
            return transfers;
        }

        private List<DatabaseManager.StoredBlock> blocks() {
            return blocks;
        }

        private Instant timestamp() {
            Instant latest = Instant.MIN;
            for (DatabaseManager.StoredBlock block : blocks) {
                if (block.log().timestamp().isAfter(latest)) latest = block.log().timestamp();
            }
            for (DatabaseManager.StoredTransfer transfer : transfers) {
                if (transfer.log().timestamp().isAfter(latest)) latest = transfer.log().timestamp();
            }
            return latest;
        }

        private String actorName() {
            for (DatabaseManager.StoredTransfer transfer : transfers) {
                String name = transfer.log().actorName();
                if (name != null && !name.isBlank()) return name;
            }
            for (DatabaseManager.StoredBlock block : blocks) return block.log().playerName();
            return "UNKNOWN";
        }

        private Endpoint primaryEndpoint() {
            for (DatabaseManager.StoredTransfer transfer : transfers) {
                TransferLog log = transfer.log();
                if (log.source().type() != EndpointType.PLAYER) return log.source();
                if (log.destination().type() != EndpointType.PLAYER) return log.destination();
            }
            return null;
        }
    }

    private record Result(List<DatabaseManager.StoredBlock> blocks, List<DatabaseManager.StoredTransfer> transfers, Exception error) {}
}
