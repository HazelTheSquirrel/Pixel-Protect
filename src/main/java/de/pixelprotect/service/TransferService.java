package de.pixelprotect.service;

import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import de.pixelprotect.model.Owner;
import de.pixelprotect.model.TransferLog;
import de.pixelprotect.util.EndpointResolver;
import de.pixelprotect.util.ItemCodec;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class TransferService {
    private static final long PLAYER_TRANSACTION_WINDOW_MILLIS = 1500L;

    private final AsyncLogQueue queue;
    private final OwnershipService ownership;
    private final ConcurrentHashMap<String, ActiveTransaction> activeTransactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> openSessions = new ConcurrentHashMap<>();

    public TransferService(org.bukkit.plugin.java.JavaPlugin plugin, AsyncLogQueue queue, OwnershipService ownership) {
        this.queue = queue;
        this.ownership = ownership;
    }

    public void inventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Endpoint endpoint = EndpointResolver.resolve(event.getInventory());
        if (endpoint == null || endpoint.type() == EndpointType.PLAYER) return;
        openSessions.put(sessionKey(player, endpoint), UUID.randomUUID());
    }

    public void inventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Endpoint endpoint = EndpointResolver.resolve(event.getInventory());
        if (endpoint == null || endpoint.type() == EndpointType.PLAYER) return;
        openSessions.remove(sessionKey(player, endpoint));
    }

    public void playerClick(InventoryClickEvent event) {
        if (event.isCancelled()) return;
        Player player = (Player) event.getWhoClicked();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;

        Endpoint playerEndpoint = Endpoint.player(player.getUniqueId(), player.getName());
        Endpoint clickedEndpoint = EndpointResolver.resolve(clicked);
        if (clickedEndpoint == null) return;

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        InventoryAction action = event.getAction();
        ItemStack candidate = current != null && !current.isEmpty() ? current.clone() : cursor != null && !cursor.isEmpty() ? cursor.clone() : null;
        if (candidate == null) return;

        int amount = movedAmount(action, current, cursor, clicked);
        if (amount <= 0) return;

        Endpoint source;
        Endpoint destination;
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (clickedEndpoint.type() == EndpointType.PLAYER) {
                source = playerEndpoint;
                destination = EndpointResolver.resolve(event.getView().getTopInventory());
            } else {
                source = clickedEndpoint;
                destination = playerEndpoint;
            }
            if (destination == null) return;
        } else if (isPickup(action)) {
            if (clickedEndpoint.type() == EndpointType.PLAYER) return;
            source = clickedEndpoint;
            destination = playerEndpoint;
        } else if (isPlace(action)) {
            if (clickedEndpoint.type() == EndpointType.PLAYER) return;
            source = playerEndpoint;
            destination = clickedEndpoint;
        } else if (action == InventoryAction.SWAP_WITH_CURSOR) {
            if (clickedEndpoint.type() == EndpointType.PLAYER) return;
            source = playerEndpoint;
            destination = clickedEndpoint;
        } else {
            return;
        }

        submitPlayerTransfer(player, source, destination, candidate, amount, "PLAYER_TRANSFER");
    }

    public void playerDrag(InventoryDragEvent event) {
        if (event.isCancelled()) return;
        Player player = (Player) event.getWhoClicked();
        ItemStack oldCursor = event.getOldCursor();
        if (oldCursor == null || oldCursor.isEmpty() || event.getNewItems().isEmpty()) return;

        int amount = event.getNewItems().values().stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .mapToInt(ItemStack::getAmount)
                .sum();
        if (amount <= 0) return;

        Inventory target = null;
        for (Integer raw : event.getRawSlots()) {
            Inventory inventory = event.getView().getInventory(raw);
            if (inventory != null && inventory != event.getView().getBottomInventory()) {
                target = inventory;
                break;
            }
        }
        if (target == null) return;

        Endpoint destination = EndpointResolver.resolve(target);
        if (destination == null || destination.type() == EndpointType.PLAYER) return;

        submitPlayerTransfer(player,
                Endpoint.player(player.getUniqueId(), player.getName()),
                destination,
                oldCursor.clone(),
                amount,
                event.getType() == DragType.EVEN ? "PLAYER_DRAG" : "PLAYER_DRAG_SINGLE");
    }

    public void automatedMove(InventoryMoveItemEvent event) {
        if (event.isCancelled()) return;
        ItemStack item = event.getItemStack();
        if (item == null || item.isEmpty()) return;

        Endpoint source = EndpointResolver.resolve(event.getSource());
        Endpoint destination = EndpointResolver.resolve(event.getDestination());
        Endpoint initiator = EndpointResolver.resolve(event.getInitiator());
        if (source == null || destination == null) return;

        UUID tx = UUID.randomUUID();
        Instant now = Instant.now();
        CompletableFuture.runAsync(() -> {
            Owner owner = initiator == null ? null : ownership.load(initiator);
            if (owner == null) owner = ownership.load(source);
            if (owner == null) owner = ownership.load(destination);
            submitAutomated(tx, now, owner, source, destination, item);
        });
    }

    public void groundToInventory(InventoryPickupItemEvent event) {
        if (event.isCancelled()) return;
        Item item = event.getItem();
        if (item == null || item.isDead()) return;
        ItemStack stack = item.getItemStack();
        Endpoint destination = EndpointResolver.resolve(event.getInventory());
        if (destination == null) return;
        Endpoint source = ground(item);
        Instant now = Instant.now();
        UUID tx = UUID.randomUUID();
        CompletableFuture.runAsync(() -> {
            Owner owner = ownership.load(destination);
            submitAutomated(tx, now, owner, source, destination, stack);
        });
    }

    public void groundToPlayer(EntityPickupItemEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player player)) return;
        Item item = event.getItem();
        if (item == null || item.isDead()) return;
        ItemStack stack = item.getItemStack();
        Endpoint source = ground(item);
        Endpoint destination = Endpoint.player(player.getUniqueId(), player.getName());
        submitPlayerTransfer(player, source, destination, stack.clone(), stack.getAmount(), "GROUND_PICKUP");
    }

    private void submitAutomated(UUID tx, Instant now, Owner owner, Endpoint source, Endpoint destination, ItemStack item) {
        UUID actorUuid = owner == null ? null : owner.uuid();
        String actorName = owner == null ? "UNKNOWN" : owner.name();
        queue.submitTransfer(new TransferLog(tx, now, actorUuid, actorName, actorUuid, actorName,
                source, destination, ItemCodec.key(item), ItemCodec.encode(item), item.getAmount(), "AUTOMATED_TRANSFER"));
    }

    private static Endpoint ground(Item item) {
        var location = item.getLocation();
        return Endpoint.block(EndpointType.GROUND, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), "ground");
    }

    private void submitPlayerTransfer(Player player, Endpoint source, Endpoint destination, ItemStack item, int amount, String action) {
        if (amount <= 0) return;
        item.setAmount(Math.min(amount, item.getMaxStackSize()));
        UUID transactionId = sessionTransaction(player, source, destination);
        queue.submitTransfer(new TransferLog(transactionId, Instant.now(), player.getUniqueId(), player.getName(),
                player.getUniqueId(), player.getName(), source, destination, ItemCodec.key(item), ItemCodec.encode(item), amount, action));
    }

    private UUID sessionTransaction(Player player, Endpoint source, Endpoint destination) {
        Endpoint external = source.type() == EndpointType.PLAYER ? destination : destination.type() == EndpointType.PLAYER ? source : null;
        if (external != null) {
            UUID session = openSessions.get(sessionKey(player, external));
            if (session != null) return session;
        }
        return transactionId(player, source, destination);
    }

    private UUID transactionId(Player player, Endpoint source, Endpoint destination) {
        long now = System.currentTimeMillis();
        String endpointKey = transactionEndpointKey(source, destination);
        String key = player.getUniqueId() + "|" + endpointKey;
        ActiveTransaction active = activeTransactions.get(key);
        if (active != null && now - active.lastActivityMillis() <= PLAYER_TRANSACTION_WINDOW_MILLIS) {
            activeTransactions.put(key, new ActiveTransaction(active.id(), now));
            return active.id();
        }
        UUID id = UUID.randomUUID();
        activeTransactions.put(key, new ActiveTransaction(id, now));
        cleanupTransactions(now);
        return id;
    }

    private static String transactionEndpointKey(Endpoint source, Endpoint destination) {
        if (source.type() == EndpointType.PLAYER && destination.type() != EndpointType.PLAYER) return destination.key();
        if (destination.type() == EndpointType.PLAYER && source.type() != EndpointType.PLAYER) return source.key();
        String a = source.key();
        String b = destination.key();
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static String sessionKey(Player player, Endpoint endpoint) {
        return player.getUniqueId() + "|" + endpoint.key();
    }

    private void cleanupTransactions(long now) {
        activeTransactions.entrySet().removeIf(entry -> now - entry.getValue().lastActivityMillis() > PLAYER_TRANSACTION_WINDOW_MILLIS * 4);
    }

    private static boolean isPickup(InventoryAction action) {
        return switch (action) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, COLLECT_TO_CURSOR -> true;
            default -> false;
        };
    }

    private static boolean isPlace(InventoryAction action) {
        return switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME -> true;
            default -> false;
        };
    }

    private static int movedAmount(InventoryAction action, ItemStack current, ItemStack cursor, Inventory clicked) {
        int currentAmount = current == null || current.isEmpty() ? 0 : current.getAmount();
        int cursorAmount = cursor == null || cursor.isEmpty() ? 0 : cursor.getAmount();
        return switch (action) {
            case PICKUP_ALL -> currentAmount;
            case PICKUP_HALF -> (currentAmount + 1) / 2;
            case PICKUP_ONE -> Math.min(1, currentAmount);
            case PICKUP_SOME -> Math.max(0, Math.min(currentAmount, Math.max(0, current.getMaxStackSize() - cursorAmount)));
            case PLACE_ALL -> cursorAmount;
            case PLACE_ONE -> Math.min(1, cursorAmount);
            case PLACE_SOME -> Math.max(0, Math.min(cursorAmount, Math.max(0, clicked.getMaxStackSize() - currentAmount)));
            case MOVE_TO_OTHER_INVENTORY -> currentAmount;
            case COLLECT_TO_CURSOR -> currentAmount;
            case SWAP_WITH_CURSOR -> cursorAmount;
            default -> 0;
        };
    }

    private record ActiveTransaction(UUID id, long lastActivityMillis) {}
}
