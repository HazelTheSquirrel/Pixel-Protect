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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Player activity and interaction coverage complementing world/container logging. */
public final class ActivityAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;
    public ActivityAuditListener(Plugin plugin, AuditService audit) { this.plugin = plugin; this.audit = audit; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) { recordPlayer(event.getPlayer(), ActionType.SESSION, "LOGIN"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) { recordPlayer(event.getPlayer(), ActionType.SESSION, "LOGOUT"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) { recordPlayer(event.getPlayer(), ActionType.SESSION, "WORLD_CHANGE"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        recordPlayer(event.getPlayer(), ActionType.ENTITY_DEATH, "PLAYER_DEATH:" + safe(event.deathMessage()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().isBlank()) recordPlayer(event.getPlayer(), ActionType.COMMAND, event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        String text = event.message().toString();
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            Player player = Bukkit.getPlayer(uuid);
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
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Block block = event.getBlock();
        BlockSnapshot before = BlockSnapshot.capture(block);
        StringBuilder lines = new StringBuilder();
        for (var line : event.lines()) { if (!lines.isEmpty()) lines.append('\n'); lines.append(safe(line)); }
        Bukkit.getRegionScheduler().run(plugin, block.getWorld(), block.getChunk().getX(), block.getChunk().getZ(), task ->
                audit.recordPlayer(block, ActionType.SIGN, event.getPlayer(), before,
                        new BlockSnapshot(before.blockData(), before.inventory(), "activity:sign:" + escape(lines.toString()))));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            Entity target = event.getEntity();
            record(player, target.getLocation().getBlock(), ActionType.ENTITY_DAMAGE,
                    target.getType().getKey() + ":" + event.getCause().name());
        }
    }

    private void recordPlayer(Player player, ActionType action, String detail) { record(player, player.getLocation().getBlock(), action, detail); }
    private void record(Player player, Block block, ActionType action, String detail) {
        if (player == null || block == null) return;
        BlockSnapshot snapshot = activitySnapshot(block, detail);
        audit.recordPlayer(block, action, player, snapshot, snapshot);
    }
    private void recordEnvironment(Block block, ActionType action, String detail) {
        if (block == null) return;
        BlockSnapshot snapshot = activitySnapshot(block, detail);
        audit.recordEnvironment(block, action, snapshot, snapshot);
    }
    private static BlockSnapshot activitySnapshot(Block block, String detail) {
        return new BlockSnapshot(block.getBlockData().getAsString(), null, "activity:" + escape(detail));
    }
    private static String escape(Object value) { return safe(value).replace("\\", "\\\\").replace("\n", "\\n").replace("|", "\\|"); }
    private static String safe(Object value) { return value == null ? "" : value.toString().replace('\u0000', ' '); }
}
