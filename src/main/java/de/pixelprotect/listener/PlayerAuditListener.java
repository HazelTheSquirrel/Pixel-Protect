package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

public final class PlayerAuditListener implements Listener {
    private final AuditService audit;

    public PlayerAuditListener(AuditService audit) {
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        final var block = event.getBlock();
        final var before = BlockSnapshot.capture(block);
        audit.recordPlayer(block, ActionType.BUCKET, event.getPlayer(), before, BlockSnapshot.capture(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        final var block = event.getBlock();
        audit.recordPlayer(block, ActionType.BUCKET, event.getPlayer(), BlockSnapshot.capture(block),
                BlockSnapshot.capture(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        final var entity = event.getEntity();
        audit.recordEnvironment(entity.getLocation().getBlock(), ActionType.ITEM_DROP,
                new BlockSnapshot("minecraft:air", null), entitySnapshot(entity));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDrop(EntityDropItemEvent event) {
        final var item = event.getItemDrop();
        audit.recordEntity(item.getLocation().getBlock(), ActionType.ITEM_DROP, event.getEntity(),
                new BlockSnapshot("minecraft:air", null), entitySnapshot(item));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        final var item = event.getItem();
        audit.recordEntity(item.getLocation().getBlock(), ActionType.ITEM_PICKUP, event.getEntity(),
                entitySnapshot(item), new BlockSnapshot("minecraft:air", null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        final var item = event.getEntity();
        audit.recordEnvironment(item.getLocation().getBlock(), ActionType.ITEM_DESPAWN,
                entitySnapshot(item), new BlockSnapshot("minecraft:air", null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        final var entity = event.getEntity();
        audit.recordEnvironment(entity.getLocation().getBlock(), ActionType.ENTITY_DEATH,
                entitySnapshot(entity), new BlockSnapshot("minecraft:air", null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent event) {
        final var entity = event.getEntity();
        audit.recordEnvironment(entity.getLocation().getBlock(), ActionType.ENTITY_REMOVE,
                entitySnapshot(entity), new BlockSnapshot("minecraft:air", null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        final var entity = event.getEntity();
        audit.recordEnvironment(entity.getLocation().getBlock(), ActionType.ENTITY_DAMAGE,
                entitySnapshot(entity), new BlockSnapshot(entitySnapshot(entity).blockData() + ";damage=" + event.getDamage(), null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        final var projectile = event.getEntity();
        final var location = event.getHitBlock() != null ? event.getHitBlock() : projectile.getLocation().getBlock();
        audit.recordEntity(location, ActionType.PROJECTILE, projectile,
                entitySnapshot(projectile), new BlockSnapshot("minecraft:air", null));
    }

    private static BlockSnapshot entitySnapshot(org.bukkit.entity.Entity entity) {
        final String name = entity.getName().replace(';', '_').replace('=', '_');
        return new BlockSnapshot("pixelprotect:entity;type=" + entity.getType().getKey() + ";uuid=" + entity.getUniqueId() + ";name=" + name, null);
    }
}
