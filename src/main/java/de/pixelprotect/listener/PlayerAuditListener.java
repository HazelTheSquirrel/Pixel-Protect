package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.model.EntitySnapshot;
import de.pixelprotect.service.AuditService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.plugin.Plugin;
import java.util.UUID;

public final class PlayerAuditListener implements Listener {
    private final Plugin plugin;private final AuditService audit;
    public PlayerAuditListener(Plugin plugin,AuditService audit){this.plugin=plugin;this.audit=audit;}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onBucketEmpty(PlayerBucketEmptyEvent e){var b=e.getBlock();var before=BlockSnapshot.capture(b);later(b,()->audit.recordPlayer(b,ActionType.BUCKET,e.getPlayer(),before,BlockSnapshot.capture(b)));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onBucketFill(PlayerBucketFillEvent e){var b=e.getBlock();var before=BlockSnapshot.capture(b);later(b,()->audit.recordPlayer(b,ActionType.BUCKET,e.getPlayer(),before,BlockSnapshot.capture(b)));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onCreatureSpawn(CreatureSpawnEvent e){recordSpawn(e.getEntity(),ActionType.ENTITY_SPAWN,e.getSpawnReason().name(),e.getEntity().getEntitySpawnReason()==null?null:e.getEntity().getEntitySpawnReason().name());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onItemDrop(EntityDropItemEvent e){recordSpawn(e.getItemDrop(),ActionType.ITEM_DROP,"DROP",e.getEntity().getUniqueId().toString());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onItemPickup(EntityPickupItemEvent e){var item=e.getItem();audit.recordEntity(item.getLocation().getBlock(),ActionType.ITEM_PICKUP,e.getEntity(),new BlockSnapshot(EntitySnapshot.capture(item,"PICKUP",e.getEntity().getUniqueId().toString()).serialize(),null),new BlockSnapshot("minecraft:air",null));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onItemDespawn(ItemDespawnEvent e){var item=e.getEntity();audit.recordEnvironment(item.getLocation().getBlock(),ActionType.ITEM_DESPAWN,new BlockSnapshot(EntitySnapshot.capture(item,"DESPAWN",null).serialize(),null),new BlockSnapshot("minecraft:air",null));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onEntityDeath(EntityDeathEvent e){var x=e.getEntity();String cause=x.getLastDamageCause()==null?"UNKNOWN":x.getLastDamageCause().getCause().name();audit.recordEnvironment(x.getLocation().getBlock(),ActionType.ENTITY_DEATH,new BlockSnapshot(EntitySnapshot.capture(x,"DEATH:"+cause,null).serialize(),null),new BlockSnapshot("minecraft:air",null));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onEntityRemove(EntityRemoveEvent e){var x=e.getEntity();String cause=x.getRemoveEventCause()==null?"UNKNOWN":x.getRemoveEventCause().name();audit.recordEnvironment(x.getLocation().getBlock(),ActionType.ENTITY_REMOVE,new BlockSnapshot(EntitySnapshot.capture(x,"REMOVE:"+cause,null).serialize(),null),new BlockSnapshot("minecraft:air",null));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onProjectileHit(ProjectileHitEvent e){var x=e.getEntity();var hit=e.getHitEntity();String actor=hit==null?null:hit.getUniqueId().toString();var b=e.getHitBlock()!=null?e.getHitBlock():x.getLocation().getBlock();audit.recordEntity(b,ActionType.PROJECTILE,x,new BlockSnapshot(EntitySnapshot.capture(x,"PROJECTILE_HIT",actor).serialize(),null),new BlockSnapshot("minecraft:air",null));}
    private void recordSpawn(org.bukkit.entity.Entity e,ActionType action,String cause,String actor){var b=e.getLocation().getBlock();audit.recordEnvironment(b,action,new BlockSnapshot("minecraft:air",null),new BlockSnapshot(EntitySnapshot.capture(e,cause,actor).serialize(),null));}
    private void later(org.bukkit.block.Block b,Runnable r){Bukkit.getRegionScheduler().runDelayed(plugin,b.getWorld(),b.getChunk().getX(),b.getChunk().getZ(),ignored->r.run(),1L);}
}
