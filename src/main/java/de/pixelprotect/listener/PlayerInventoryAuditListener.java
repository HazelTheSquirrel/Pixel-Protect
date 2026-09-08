package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Captures player-owned inventory transitions without treating them as block/container rollback data. */
public final class PlayerInventoryAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;

    public PlayerInventoryAuditListener(Plugin plugin, AuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClickBefore(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!touchesPlayerInventory(event)) return;
        PlayerState.capture(plugin, audit, player, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onClickAfter(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        PlayerState.finish(audit, player, event, "INVENTORY_CLICK:" + event.getClick().name());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDragBefore(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        PlayerState.capture(plugin, audit, player, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDragAfter(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        PlayerState.finish(audit, player, event, "INVENTORY_DRAG");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Block block = player.getLocation().getBlock();
        ItemStack dropped = event.getItemDrop().getItemStack();
        BlockSnapshot after = snapshot(player);
        audit.recordPlayer(block, ActionType.INVENTORY, player, after, after,
                "PLAYER_DROP:" + item(dropped), audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Block block = player.getLocation().getBlock();
        ItemStack picked = event.getItem().getItemStack();
        BlockSnapshot after = snapshot(player);
        audit.recordPlayer(block, ActionType.INVENTORY, player, after, after,
                "PLAYER_PICKUP:" + item(picked), audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Block afterBlock = player.getLocation().getBlock();
        BlockSnapshot after = snapshot(player);
        audit.recordPlayer(afterBlock, ActionType.INVENTORY, player, after, after,
                "PLAYER_CONSUME:" + item(event.getItem()), audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        Block block = player.getLocation().getBlock();
        BlockSnapshot after = snapshot(player);
        audit.recordPlayer(block, ActionType.INVENTORY, player, after, after,
                "PLAYER_SWAP_HANDS:MAIN=" + item(event.getMainHandItem()) + ":OFF=" + item(event.getOffHandItem()), audit.newTransaction(), 0L);
    }

    private static boolean touchesPlayerInventory(InventoryClickEvent event) {
        return event.getClickedInventory() == event.getView().getBottomInventory()
                || event.getClick().isShiftClick()
                || event.getClick().isKeyboardClick();
    }

    private static BlockSnapshot snapshot(Player player) {
        return new BlockSnapshot(player.getLocation().getBlock().getBlockData().getAsString(),
                ItemStack.serializeItemsAsBytes(player.getInventory().getContents()), null);
    }

    private static String item(ItemStack item) {
        return item == null ? "air" : item.getType().getKey() + ":" + item.getAmount();
    }

    private static final class PlayerState {
        private static final java.util.Map<Object, BlockSnapshot> BEFORE = new java.util.concurrent.ConcurrentHashMap<>();

        private static void capture(Plugin plugin, AuditService audit, Player player, Object event) {
            if (!audit.isWorldIncluded(player.getWorld().getUID())) return;
            BEFORE.put(event, snapshot(player));
        }

        private static void finish(AuditService audit, Player player, Object event, String detail) {
            BlockSnapshot before = BEFORE.remove(event);
            if (before == null) return;
            BlockSnapshot after = snapshot(player);
            if (java.util.Arrays.equals(before.inventory(), after.inventory())) return;
            audit.recordPlayer(player.getLocation().getBlock(), ActionType.INVENTORY, player, before, after,
                    detail, audit.newTransaction(), 0L);
        }
    }
}
