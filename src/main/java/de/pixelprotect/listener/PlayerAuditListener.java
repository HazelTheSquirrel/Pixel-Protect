package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.model.EntitySnapshot;
import de.pixelprotect.service.AuditService;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Player-caused block, bucket and entity lifecycle auditing. */
public final class PlayerAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;
    private final Map<Object, BlockSnapshot> bucketBefore = new ConcurrentHashMap<>();

    public PlayerAuditListener(Plugin plugin, AuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBucketEmptyBefore(PlayerBucketEmptyEvent event) { bucketBefore.put(event, BlockSnapshot.capture(event.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBucketEmptyAfter(PlayerBucketEmptyEvent event) { recordBucket(event); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBucketFillBefore(PlayerBucketFillEvent event) { bucketBefore.put(event, BlockSnapshot.capture(event.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBucketFillAfter(PlayerBucketFillEvent event) { recordBucket(event); }

    private void recordBucket(org.bukkit.event.player.PlayerBucketEvent event) {
        Block block = event.getBlock();
        BlockSnapshot before = bucketBefore.remove(event);
        if (before == null) return;
        BlockSnapshot after = BlockSnapshot.capture(block);
        if (before.blockData().equals(after.blockData())) return;
        audit.recordPlayer(block, ActionType.BUCKET, event.getPlayer(), before, after);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) { recordSpawn(event.getEntity(), ActionType.ENTITY_SPAWN, event.getSpawnReason().name()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDrop(EntityDropItemEvent event) {
        Entity source = event.getEntity();
        var item = event.getItemDrop();
        var block = item.getLocation().getBlock();
        var before = new BlockSnapshot("minecraft:air", null);
        var after = new BlockSnapshot(EntitySnapshot.capture(item, "DROP", source.getUniqueId().toString()).serialize(), null);
        if (source instanceof Player player) audit.recordPlayer(block, ActionType.ITEM_DROP, player, before, after);
        else audit.recordEnvironment(block, ActionType.ITEM_DROP, before, after);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        var item = event.getItem();
        var block = item.getLocation().getBlock();
        var before = new BlockSnapshot(EntitySnapshot.capture(item, "PICKUP", event.getEntity().getUniqueId().toString()).serialize(), null);
        var after = new BlockSnapshot("minecraft:air", null);
        if (event.getEntity() instanceof Player player) audit.recordPlayer(block, ActionType.ITEM_PICKUP, player, before, after);
        else audit.recordEnvironment(block, ActionType.ITEM_PICKUP, before, after);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        var item = event.getEntity();
        audit.recordEnvironment(item.getLocation().getBlock(), ActionType.ITEM_DESPAWN,
                new BlockSnapshot(EntitySnapshot.capture(item, "DESPAWN", null).serialize(), null), new BlockSnapshot("minecraft:air", null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        var entity = event.getEntity();
        String cause = entity.getLastDamageCause() == null ? "UNKNOWN" : entity.getLastDamageCause().getCause().name();
        Entity damager = null;
        if (entity.getLastDamageCause() instanceof org.bukkit.event.entity.EntityDamageByEntityEvent damage) damager = damage.getDamager();
        var before = new BlockSnapshot(EntitySnapshot.capture(entity, "DEATH:" + cause, damager == null ? null : damager.getUniqueId().toString()).serialize(), null);
        var after = new BlockSnapshot("minecraft:air", null);
        if (damager instanceof Player player) audit.recordPlayer(entity.getLocation().getBlock(), ActionType.ENTITY_DEATH, player, before, after);
        else audit.recordEnvironment(entity.getLocation().getBlock(), ActionType.ENTITY_DEATH, before, after);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent event) {
        var entity = event.getEntity();
        audit.recordEnvironment(entity.getLocation().getBlock(), ActionType.ENTITY_REMOVE,
                new BlockSnapshot(EntitySnapshot.capture(entity, "REMOVE:" + event.getCause().name(), null).serialize(), null), new BlockSnapshot("minecraft:air", null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        var projectile = event.getEntity();
        var hit = event.getHitEntity();
        var block = event.getHitBlock() != null ? event.getHitBlock() : projectile.getLocation().getBlock();
        String actor = hit == null ? null : hit.getUniqueId().toString();
        var before = new BlockSnapshot(EntitySnapshot.capture(projectile, "PROJECTILE_HIT", actor).serialize(), null);
        var after = new BlockSnapshot("minecraft:air", null);
        if (projectile.getShooter() instanceof Player player) audit.recordPlayer(block, ActionType.PROJECTILE, player, before, after);
        else audit.recordEnvironment(block, ActionType.PROJECTILE, before, after);
    }

    private void recordSpawn(Entity entity, ActionType action, String cause) {
        var block = entity.getLocation().getBlock();
        audit.recordEnvironment(block, action, new BlockSnapshot("minecraft:air", null), new BlockSnapshot(EntitySnapshot.capture(entity, cause, null).serialize(), null));
    }
}
