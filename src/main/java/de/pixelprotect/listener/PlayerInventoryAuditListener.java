package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Captures exact player-owned inventory slot transitions without treating them as block rollback data. */
public final class PlayerInventoryAuditListener implements Listener {
    private final AuditService audit;

    public PlayerInventoryAuditListener(Plugin plugin, AuditService audit) {
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        Player player = event.getPlayer();
        int slot = event.getSlot();
        ItemStack[] beforeItems = player.getInventory().getContents().clone();
        ItemStack[] afterItems = player.getInventory().getContents().clone();
        if (slot < 0 || slot >= afterItems.length) return;
        beforeItems[slot] = cloneOrNull(event.getOldItemStack());
        afterItems[slot] = cloneOrNull(event.getNewItemStack());

        Block block = player.getLocation().getBlock();
        BlockSnapshot before = snapshot(block, beforeItems);
        BlockSnapshot after = snapshot(block, afterItems);
        if (java.util.Arrays.equals(before.inventory(), after.inventory())) return;
        String detail = "SLOT=" + slot + ":RAW=" + event.getRawSlot()
                + ":OLD=" + item(event.getOldItemStack()) + ":NEW=" + item(event.getNewItemStack());
        audit.recordPlayer(block, ActionType.INVENTORY, player, before, after, detail, audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        recordActivity(player, "PLAYER_DROP:" + item(event.getItemDrop().getItemStack()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player)
            recordActivity(player, "PLAYER_PICKUP:" + item(event.getItem().getItemStack()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        recordActivity(event.getPlayer(), "PLAYER_CONSUME:" + item(event.getItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        recordActivity(event.getPlayer(), "PLAYER_SWAP_HANDS:MAIN=" + item(event.getMainHandItem()) + ":OFF=" + item(event.getOffHandItem()));
    }

    private void recordActivity(Player player, String detail) {
        Block block = player.getLocation().getBlock();
        BlockSnapshot snapshot = snapshot(block, player.getInventory().getContents());
        audit.recordPlayer(block, ActionType.INVENTORY, player, snapshot, snapshot, detail, audit.newTransaction(), 0L);
    }

    private static BlockSnapshot snapshot(Block block, ItemStack[] contents) {
        return new BlockSnapshot(block.getBlockData().getAsString(), ItemStack.serializeItemsAsBytes(contents), null);
    }

    private static ItemStack cloneOrNull(ItemStack item) { return item == null ? null : item.clone(); }

    private static String item(ItemStack item) {
        return item == null ? "air" : item.getType().getKey() + ":" + item.getAmount();
    }
}
