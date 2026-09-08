package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

/** Player activity and interaction coverage. Activity records never masquerade as block-entity snapshots. */
public final class ActivityAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;

    public ActivityAuditListener(Plugin plugin, AuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) { recordPlayer(event.getPlayer(), ActionType.SESSION, "LOGIN"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) { recordPlayer(event.getPlayer(), ActionType.SESSION, "LOGOUT"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) { recordPlayer(event.getPlayer(), ActionType.SESSION, "WORLD_CHANGE"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) { recordPlayer(event.getPlayer(), ActionType.ENTITY_DEATH, "PLAYER_DEATH:" + safe(event.deathMessage())); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().isBlank()) recordPlayer(event.getPlayer(), ActionType.COMMAND, event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        UUIDHolder holder = new UUIDHolder(event.getPlayer().getUniqueId());
        String text = event.message().toString();
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            Player player = Bukkit.getPlayer(holder.uuid());
            if (player != null) recordPlayer(player, ActionType.CHAT, text);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block != null) record(event.getPlayer(), block, ActionType.INTERACT, event.getAction().name() + ":" + block.getType().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        record(event.getPlayer(), target.getLocation().getBlock(), ActionType.ENTITY_INTERACT,
                target.getType().getKey() + ":" + target.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Entity entity = event.getEntity();
        record(event.getPlayer(), entity.getLocation().getBlock(), ActionType.PLACE, "HANGING:" + entity.getType().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Entity entity = event.getEntity();
        if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION)
            recordEnvironment(entity.getLocation().getBlock(), ActionType.EXPLOSION, "HANGING:" + entity.getType().getKey());
        else if (event.getCause() == HangingBreakEvent.RemoveCause.ENTITY)
            recordEnvironment(entity.getLocation().getBlock(), ActionType.ENTITY_REMOVE, "HANGING:" + entity.getType().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Block block = event.getBlock();
        BlockSnapshot before = BlockSnapshot.capture(block);
        StringBuilder lines = new StringBuilder();
        for (var line : event.lines()) {
            if (!lines.isEmpty()) lines.append('\n');
            lines.append(safe(line));
        }
        Bukkit.getRegionScheduler().run(plugin, block.getWorld(), block.getChunk().getX(), block.getChunk().getZ(), task ->
                audit.recordPlayer(block, ActionType.SIGN, event.getPlayer(), before, BlockSnapshot.capture(block),
                        "LINES:" + escape(lines), audit.newTransaction(), 0L));
    }

    private void recordPlayer(Player player, ActionType action, String detail) { record(player, player.getLocation().getBlock(), action, detail); }

    private void record(Player player, Block block, ActionType action, String detail) {
        if (player == null || block == null) return;
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.recordPlayer(block, action, player, snapshot, snapshot, detail, audit.newTransaction(), 0L);
    }

    private void recordEnvironment(Block block, ActionType action, String detail) {
        if (block == null) return;
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.recordEnvironment(block, action, snapshot, snapshot, detail, audit.newTransaction(), 0L);
    }

    private static String escape(Object value) { return safe(value).replace("\\", "\\\\").replace("\n", "\\n"); }
    private static String safe(Object value) { return value == null ? "" : value.toString().replace('\u0000', ' '); }
    private record UUIDHolder(java.util.UUID uuid) {}
}
