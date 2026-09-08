package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import io.papermc.paper.event.inventory.ItemCraftedEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerLecternPageChangeEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.ItemStack;

/** Player transactions that are meaningful forensic events but are not block-state mutations. */
public final class PlayerTransactionAuditListener implements Listener {
    private final AuditService audit;

    public PlayerTransactionAuditListener(AuditService audit) { this.audit = audit; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(ItemCraftedEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getCraftedItem();
        record(player, player.getLocation().getBlock(), ActionType.CRAFT, "CRAFT:" + item.getType().getKey() + ":" + item.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        Player player = event.getPlayer();
        record(player, event.getVillager().getLocation().getBlock(), ActionType.TRADE,
                "TRADE:" + event.getTrade().getResult().getType().getKey() + ":uses=" + event.getTrade().getUses());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemFrame(PlayerItemFrameChangeEvent event) {
        record(event.getPlayer(), event.getItemFrame().getLocation().getBlock(), ActionType.ENTITY_INTERACT,
                "ITEM_FRAME:" + event.getAction().name() + ":" + event.getItemStack().getType().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInsertLectern(PlayerInsertLecternBookEvent event) {
        record(event.getPlayer(), event.getBlock(), ActionType.CONTAINER, "LECTERN_INSERT:" + event.getBook().getType().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTakeLectern(PlayerTakeLecternBookEvent event) {
        record(event.getPlayer(), event.getLectern().getBlock(), ActionType.CONTAINER,
                "LECTERN_TAKE:" + (event.getBook() == null ? "air" : event.getBook().getType().getKey()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLecternPage(PlayerLecternPageChangeEvent event) {
        record(event.getPlayer(), event.getLectern().getBlock(), ActionType.INTERACT,
                "LECTERN_PAGE:" + event.getOldPage() + "->" + event.getNewPage());
    }

    private void record(Player player, Block block, ActionType action, String detail) {
        if (player == null || block == null) return;
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.recordPlayer(block, action, player, snapshot, snapshot, detail, audit.newTransaction(), 0L);
    }
}
