package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.plugin.Plugin;

/** Modern player mechanics which materially affect inventories, entities or world history. */
public final class PlayerMechanicsAuditListener implements Listener {
    private final AuditService audit;

    public PlayerMechanicsAuditListener(Plugin plugin, AuditService audit) {
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getEntity();
        Block block = entity.getLocation().getBlock();
        String detail = "BUCKET_ENTITY:" + entity.getType().getKey() + ":ENTITY=" + entity.getUniqueId()
                + ":BUCKET=" + event.getEntityBucket().getType().getKey();
        record(player, block, ActionType.BUCKET, detail);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getRightClicked();
        String detail = "ARMOR_STAND:" + event.getSlot().name()
                + ":PLAYER_ITEM=" + item(event.getPlayerItem())
                + ":ENTITY_ITEM=" + item(event.getArmorStandItem());
        record(player, entity.getLocation().getBlock(), ActionType.ENTITY_INTERACT, detail);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlowerPot(PlayerFlowerPotManipulateEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        record(player, block, ActionType.FLOWER_POT, "FLOWER_POT_MANIPULATE:" + block.getType().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        Entity entity = event.getEntity();
        String detail = "PLAYER_SHEAR:" + entity.getType().getKey() + ":ENTITY=" + entity.getUniqueId()
                + ":DROPS=" + event.getDrops().size();
        record(event.getPlayer(), entity.getLocation().getBlock(), ActionType.SHEAR, detail);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        Block block = event.getHarvestedBlock();
        String detail = "HARVEST:" + block.getType().getKey() + ":DROPS=" + event.getItemsHarvested().size();
        record(event.getPlayer(), block, ActionType.GROW, detail);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemBreak(PlayerItemBreakEvent event) {
        Player player = event.getPlayer();
        record(player, player.getLocation().getBlock(), ActionType.INVENTORY,
                "ITEM_BREAK:" + event.getBrokenItem().getType().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        String caught = event.getCaught() == null ? "none" : event.getCaught().getType().getKey();
        record(event.getPlayer(), event.getPlayer().getLocation().getBlock(), ActionType.INTERACT,
                "FISH:" + event.getState().name() + ":CAUGHT=" + caught);
    }

    private void record(Player player, Block block, ActionType action, String detail) {
        if (player == null || block == null) return;
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.recordPlayer(block, action, player, snapshot, snapshot, detail, audit.newTransaction(), 0L);
    }

    private static String item(org.bukkit.inventory.ItemStack item) {
        return item == null ? "air" : item.getType().getKey() + ":" + item.getAmount();
    }
}
