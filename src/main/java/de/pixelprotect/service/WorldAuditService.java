package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.UUID;

public final class WorldAuditService {
    private final JavaPlugin plugin; private final AsyncLogQueue queue; private final OwnershipService ownership; private final DatabaseManager database;
    public WorldAuditService(JavaPlugin plugin,AsyncLogQueue queue,OwnershipService ownership,DatabaseManager database){this.plugin=plugin;this.queue=queue;this.ownership=ownership;this.database=database;}
    public void blockPlace(BlockPlaceEvent event){
        if(event.isCancelled())return; Block block=event.getBlockPlaced(); BlockState replaced=event.getBlockReplacedState();
        queue.submit(new BlockLog(Instant.now(),UUID.randomUUID(),event.getPlayer().getUniqueId(),event.getPlayer().getName(),block.getWorld().getName(),block.getX(),block.getY(),block.getZ(),replaced.getBlockData().getAsString(),block.getBlockData().getAsString(),"PLACE",block.getType().getKey().toString()));
        if(block.getState() instanceof InventoryHolder)ownership.record(Endpoint.block(block.getLocation(),block.getType().getKey().toString()),event.getPlayer().getUniqueId(),event.getPlayer().getName());
    }
    public void blockBreak(BlockBreakEvent event){
        if(event.isCancelled())return; Block block=event.getBlock(); String before=block.getBlockData().getAsString();
        Bukkit.getScheduler().runTask(plugin,()->queue.submit(new BlockLog(Instant.now(),UUID.randomUUID(),event.getPlayer().getUniqueId(),event.getPlayer().getName(),block.getWorld().getName(),block.getX(),block.getY(),block.getZ(),before,block.getBlockData().getAsString(),"BREAK",block.getType().getKey().toString())));
    }
    public void entityPlace(EntityPlaceEvent event){
        if(event.isCancelled()||event.getPlayer()==null)return; Entity entity=event.getEntity();
        if(entity instanceof InventoryHolder holder)ownership.record(Endpoint.entity(entity.getUniqueId(),entity.getLocation(),entity.getType().name()),event.getPlayer().getUniqueId(),event.getPlayer().getName());
    }
}
