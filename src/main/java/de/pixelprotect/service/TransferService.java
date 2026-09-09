package de.pixelprotect.service;

import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;
import de.pixelprotect.model.TransferLog;
import de.pixelprotect.util.EndpointResolver;
import de.pixelprotect.util.InventoryDiff;
import de.pixelprotect.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class TransferService {
    private final JavaPlugin plugin;
    private final AsyncLogQueue queue;
    private final OwnershipService ownership;
    private final ItemCodec codec;

    public TransferService(JavaPlugin plugin, AsyncLogQueue queue, OwnershipService ownership, ItemCodec codec) {
        this.plugin = plugin;
        this.queue = queue;
        this.ownership = ownership;
        this.codec = codec;
    }

    public void capturePlayerInventoryChange(Player player, InventoryView view) {
        Inventory top = view.getTopInventory();
        Inventory bottom = view.getBottomInventory();
        Endpoint topEndpoint = EndpointResolver.resolve(top);
        if (topEndpoint.type() != de.pixelprotect.model.EndpointType.BLOCK_CONTAINER
                && topEndpoint.type() != de.pixelprotect.model.EndpointType.MINECART) return;

        ItemStack[] topBefore = cloneContents(top.getContents());
        ItemStack[] bottomBefore = cloneContents(bottom.getContents());
        UUID transactionId = UUID.randomUUID();
        UUID chainId = UUID.randomUUID();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            ItemStack[] topAfter = cloneContents(top.getContents());
            ItemStack[] bottomAfter = cloneContents(bottom.getContents());
            Map<String, Integer> tb = InventoryDiff.totals(topBefore, codec);
            Map<String, Integer> ta = InventoryDiff.totals(topAfter, codec);
            Map<String, Integer> bb = InventoryDiff.totals(bottomBefore, codec);
            Map<String, Integer> ba = InventoryDiff.totals(bottomAfter, codec);
            Map<String, ItemStack> reps = new java.util.HashMap<>();
            reps.putAll(InventoryDiff.representatives(topBefore, codec));
            reps.putAll(InventoryDiff.representatives(topAfter, codec));
            reps.putAll(InventoryDiff.representatives(bottomBefore, codec));
            reps.putAll(InventoryDiff.representatives(bottomAfter, codec));

            for (String key : new HashSet<>(tb.keySet())) {
                int topDelta = ta.getOrDefault(key, 0) - tb.getOrDefault(key, 0);
                int bottomDelta = ba.getOrDefault(key, 0) - bb.getOrDefault(key, 0);
                ItemStack rep = reps.get(key);
                if (rep == null) continue;
                if (topDelta > 0 && bottomDelta < 0) {
                    submitPlayer(player, transactionId, chainId,
                            Endpoint.player(player.getUniqueId(), player.getName(), player.getLocation()),
                            topEndpoint, rep, Math.min(topDelta, -bottomDelta));
                } else if (topDelta < 0 && bottomDelta > 0) {
                    submitPlayer(player, transactionId, chainId, topEndpoint,
                            Endpoint.player(player.getUniqueId(), player.getName(), player.getLocation()),
                            rep, Math.min(-topDelta, bottomDelta));
                }
            }

            for (String key : ta.keySet()) {
                if (!tb.containsKey(key) && ba.getOrDefault(key, 0) < bb.getOrDefault(key, 0)) {
                    ItemStack rep = reps.get(key);
                    if (rep != null) {
                        submitPlayer(player, transactionId, chainId,
                                Endpoint.player(player.getUniqueId(), player.getName(), player.getLocation()),
                                topEndpoint, rep,
                                Math.min(ta.getOrDefault(key, 0), bb.getOrDefault(key, 0) - ba.getOrDefault(key, 0)));
                    }
                }
            }
        });
    }

    public void captureAutomation(InventoryMoveItemEvent event) {
        if (event.isCancelled()) return;
        Inventory source = event.getSource(), destination = event.getDestination(), initiator = event.getInitiator();
        Endpoint sourceEp = EndpointResolver.resolve(source), destEp = EndpointResolver.resolve(destination), initiatorEp = EndpointResolver.resolve(initiator);
        ItemStack item = event.getItem().clone();
        int destinationBefore = destination.all(item.getType()).values().stream().mapToInt(ItemStack::getAmount).sum();
        Bukkit.getScheduler().runTask(plugin, () -> {
            int destinationAfter = destination.all(item.getType()).values().stream().mapToInt(ItemStack::getAmount).sum();
            int amount = Math.min(item.getAmount(), Math.max(0, destinationAfter - destinationBefore));
            if (amount <= 0) return;
            resolveAttribution(initiatorEp).thenAccept(owner -> submitTransfer(new TransferLog(
                    Instant.now(), UUID.randomUUID(), UUID.randomUUID(), null, "Automatischer Transport",
                    owner.map(Owner::uuid).orElse(null), owner.map(Owner::name).orElse(null),
                    sourceEp, destEp, codec.key(item), codec.encode(item), amount, "AUTOMATED_TRANSFER")));
        });
    }

    public void captureHopperPickup(org.bukkit.event.inventory.InventoryPickupItemEvent event) {
        if (event.isCancelled()) return;
        Item item = event.getItem();
        ItemStack stack = item.getItemStack().clone();
        Endpoint dest = EndpointResolver.resolve(event.getInventory()), source = EndpointResolver.ground(item.getLocation());
        resolveAttribution(dest).thenAccept(owner -> submitTransfer(new TransferLog(
                Instant.now(), UUID.randomUUID(), UUID.randomUUID(), item.getThrower(),
                item.getThrower() == null ? "Boden" : name(item.getThrower()),
                owner.map(Owner::uuid).orElse(null), owner.map(Owner::name).orElse(null),
                source, dest, codec.key(stack), codec.encode(stack), stack.getAmount(), "GROUND_TO_AUTOMATION")));
    }

    public void capturePlayerDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        ItemStack stack = item.getItemStack().clone();
        Endpoint source = Endpoint.player(event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getPlayer().getLocation()), dest = Endpoint.ground(item.getLocation());
        submitTransfer(new TransferLog(Instant.now(), UUID.randomUUID(), UUID.randomUUID(), event.getPlayer().getUniqueId(), event.getPlayer().getName(), null, null, source, dest, codec.key(stack), codec.encode(stack), stack.getAmount(), "PLAYER_TO_GROUND"));
    }

    public void capturePlayerPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Item item = event.getItem();
        ItemStack stack = item.getItemStack().clone();
        Endpoint source = EndpointResolver.ground(item.getLocation()), dest = Endpoint.player(player.getUniqueId(), player.getName(), player.getLocation());
        submitTransfer(new TransferLog(Instant.now(), UUID.randomUUID(), UUID.randomUUID(), player.getUniqueId(), player.getName(), null, null, source, dest, codec.key(stack), codec.encode(stack), stack.getAmount(), "GROUND_TO_PLAYER"));
    }

    private void submitPlayer(Player player, UUID transactionId, UUID chainId, Endpoint source, Endpoint dest, ItemStack stack, int amount) {
        if (stack == null || amount <= 0) return;
        submitTransfer(new TransferLog(Instant.now(), transactionId, chainId, player.getUniqueId(), player.getName(), null, null, source, dest, codec.key(stack), codec.encode(stack), amount, "PLAYER_TRANSFER"));
    }

    private CompletableFuture<Optional<Owner>> resolveAttribution(Endpoint endpoint) {
        return ownership.resolve(endpoint).exceptionally(ex -> Optional.empty());
    }

    private void submitTransfer(TransferLog log) {
        try { queue.submit(log); } catch (RuntimeException ignored) { }
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] out = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) out[i] = contents[i] == null ? null : contents[i].clone();
        return out;
    }

    private String name(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player == null ? uuid.toString() : player.getName();
    }
}
