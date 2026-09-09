package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.util.ItemCodec;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class RollbackService {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final ItemCodec codec;

    public RollbackService(JavaPlugin plugin, DatabaseManager database, ItemCodec codec) {
        this.plugin = plugin;
        this.database = database;
        this.codec = codec;
    }

    public void rollback(Player player, String rawRollbackId) {
        final String normalized;
        try {
            normalized = normalize(rawRollbackId);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text("Pixel-Protect: " + exception.getMessage()));
            return;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return database.findRollbackId(normalized);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).thenAccept(lookup -> Bukkit.getScheduler().runTask(plugin, () -> apply(player, lookup)))
                .exceptionally(exception -> {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                            Component.text("Pixel-Protect Rollback fehlgeschlagen: " + root(exception).getMessage())));
                    return null;
                });
    }

    private void apply(Player player, DatabaseManager.RollbackLookup lookup) {
        if (!player.isOnline()) return;

        List<UUID> transactionIds = new ArrayList<>();
        lookup.transfers().forEach(t -> { if (!transactionIds.contains(t.transactionId())) transactionIds.add(t.transactionId()); });
        lookup.blocks().forEach(b -> { if (!transactionIds.contains(b.transactionId())) transactionIds.add(b.transactionId()); });

        if (transactionIds.isEmpty()) {
            player.sendMessage(Component.text("Pixel-Protect: Rollback-ID " + lookup.rollbackId() + " wurde nicht gefunden."));
            return;
        }
        if (transactionIds.size() > 1) {
            player.sendMessage(Component.text("Pixel-Protect: Rollback-ID " + lookup.rollbackId() + " ist nicht eindeutig. Bitte eine neue ID verwenden."));
            return;
        }

        List<Event> events = new ArrayList<>();
        lookup.transfers().stream().filter(t -> "ACTIVE".equals(t.rollbackState())).map(TransferEvent::new).forEach(events::add);
        lookup.blocks().stream().filter(b -> "ACTIVE".equals(b.rollbackState())).map(BlockEvent::new).forEach(events::add);
        events.sort(Comparator.comparing(Event::timestamp).reversed());

        if (events.isEmpty()) {
            player.sendMessage(Component.text("Pixel-Protect: Rollback-ID " + lookup.rollbackId() + " ist bereits zurückgerollt oder enthält keine aktiven Änderungen."));
            return;
        }

        int transfers = 0;
        int blocks = 0;
        int conflicts = 0;
        for (Event event : events) {
            try {
                if (event instanceof TransferEvent transferEvent) {
                    if (reverse(transferEvent.value())) {
                        database.markTransferRolledBack(transferEvent.value().id(), "ROLLED_BACK");
                        transfers++;
                    } else {
                        database.markTransferRolledBack(transferEvent.value().id(), "CONFLICT");
                        conflicts++;
                    }
                } else if (event instanceof BlockEvent blockEvent) {
                    if (reverseBlock(blockEvent.value())) {
                        database.markBlockRolledBack(blockEvent.value().id(), "ROLLED_BACK");
                        blocks++;
                    } else {
                        database.markBlockRolledBack(blockEvent.value().id(), "CONFLICT");
                        conflicts++;
                    }
                }
            } catch (Exception exception) {
                try {
                    if (event instanceof TransferEvent transferEvent) database.markTransferRolledBack(transferEvent.value().id(), "ERROR");
                    else if (event instanceof BlockEvent blockEvent) database.markBlockRolledBack(blockEvent.value().id(), "ERROR");
                } catch (Exception ignored) { }
                conflicts++;
            }
        }

        player.sendMessage(Component.text("Pixel-Protect Rollback " + lookup.rollbackId() + " abgeschlossen: "
                + transfers + " Item-Transfers, " + blocks + " Blockänderungen, " + conflicts + " Konflikte."));
    }

    private boolean reverse(DatabaseManager.StoredTransfer transfer) {
        Inventory source = inventory(transfer.source());
        Inventory destination = inventory(transfer.destination());
        if (source == null || destination == null) return false;

        ItemStack item = codec.decode(transfer.itemData());
        item.setAmount(transfer.amount());

        ItemStack remove = item.clone();
        Map<Integer, ItemStack> leftovers = destination.removeItemAnySlot(remove);
        int remaining = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (remaining > 0) {
            destination.addItem(remove);
            return false;
        }

        Map<Integer, ItemStack> sourceLeft = source.addItem(item);
        if (!sourceLeft.isEmpty()) {
            destination.addItem(item);
            return false;
        }
        return true;
    }

    private boolean reverseBlock(DatabaseManager.StoredBlock block) {
        World world = Bukkit.getWorld(block.world());
        if (world == null) return false;
        Block target = world.getBlockAt(block.x(), block.y(), block.z());
        if (!target.getBlockData().getAsString().equals(block.afterData())) return false;
        try {
            target.setBlockData(Bukkit.createBlockData(block.beforeData()), false);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private Inventory inventory(Endpoint endpoint) {
        return switch (endpoint.type()) {
            case PLAYER -> {
                Player player = endpoint.playerId() == null ? null : Bukkit.getPlayer(endpoint.playerId());
                yield player == null ? null : player.getInventory();
            }
            case BLOCK_CONTAINER -> {
                World world = endpoint.world() == null ? null : Bukkit.getWorld(endpoint.world());
                if (world == null) yield null;
                Block block = world.getBlockAt(endpoint.x(), endpoint.y(), endpoint.z());
                yield block.getState() instanceof InventoryHolder holder ? holder.getInventory() : null;
            }
            case MINECART -> {
                Entity entity = endpoint.entityId() == null ? null : Bukkit.getEntity(endpoint.entityId());
                yield entity instanceof InventoryHolder holder ? holder.getInventory() : null;
            }
            default -> null;
        };
    }

    private static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("Rollback-ID fehlt. Beispiel: #A7F31");
        String id = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!id.startsWith("#")) id = "#" + id;
        if (!id.matches("#[0-9A-F]{5}")) throw new IllegalArgumentException("Ungueltige Rollback-ID. Beispiel: #A7F31");
        return id;
    }

    private static Throwable root(Throwable throwable) {
        while (throwable.getCause() != null) throwable = throwable.getCause();
        return throwable;
    }

    private sealed interface Event permits TransferEvent, BlockEvent {
        java.time.Instant timestamp();
    }

    private record TransferEvent(DatabaseManager.StoredTransfer value) implements Event {
        @Override public java.time.Instant timestamp() { return value.timestamp(); }
    }

    private record BlockEvent(DatabaseManager.StoredBlock value) implements Event {
        @Override public java.time.Instant timestamp() { return value.timestamp(); }
    }
}
