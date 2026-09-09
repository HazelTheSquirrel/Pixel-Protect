package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;
import de.pixelprotect.util.EndpointResolver;
import de.pixelprotect.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.UUID;

public final class WorldAuditService {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final AsyncLogQueue queue;
    private final OwnershipService ownership;
    private final ItemCodec codec;

    public WorldAuditService(JavaPlugin plugin,DatabaseManager database,AsyncLogQueue queue,OwnershipService ownership){this.plugin=plugin;this.database=database;this.queue=queue;this.ownership=ownership;this.codec=new ItemCodec(new com.google.gson.GsonBuilder().disableHtmlEscaping().create());}
    public WorldAuditService(JavaPlugin plugin,AsyncLogQueue queue,OwnershipService ownership,DatabaseManager database){this(plugin,database,queue,ownership);}

    public void blockPlace(BlockPlaceEvent event){Player p=event.getPlayer();Block b=event.getBlockPlaced();Block old=event.getBlockReplacedState().getBlock();String before=event.getBlockReplacedState().getBlockData().getAsString();String after=b.getBlockData().getAsString();String beforeInv=inventorySnapshot(old);String afterInv=inventorySnapshot(b);log(p,b,before,after,beforeInv,afterInv,"PLACE",b.getType());if(b.getState() instanceof InventoryHolder)ownership.record(EndpointResolver.resolve(b.getState() instanceof InventoryHolder h?h.getInventory():null),new Owner(p.getUniqueId(),p.getName()));}
    public void blockBreak(BlockBreakEvent event){Player p=event.getPlayer();Block b=event.getBlock();String before=b.getBlockData().getAsString();String beforeInv=inventorySnapshot(b);String after=Bukkit.createBlockData(Material.AIR).getAsString();log(p,b,before,after,beforeInv,null,"BREAK",b.getType());if(b.getState() instanceof InventoryHolder)ownership.invalidate(EndpointResolver.resolve(((InventoryHolder)b.getState()).getInventory()));}
    public void entityPlace(EntityPlaceEvent event){if(!(event.getEntity() instanceof InventoryHolder holder))return;Entity e=event.getEntity();Player p=event.getPlayer();ownership.record(EndpointResolver.resolve(holder.getInventory()),new Owner(p.getUniqueId(),p.getName()));}

    private void log(Player p,Block b,String before,String after,String beforeInv,String afterInv,String action,Material type){long seq=database.nextSequence();String rid="#"+String.format("%010X",seq);queue.submit(new BlockLog(Instant.now(),seq,UUID.randomUUID(),rid,p.getUniqueId(),p.getName(),b.getWorld().getName(),b.getX(),b.getY(),b.getZ(),before,after,beforeInv,afterInv,action,type.getKey().toString()));}
    private String inventorySnapshot(Block b){BlockState state=b.getState();if(!(state instanceof InventoryHolder holder))return null;Inventory inv=holder.getInventory();return codec.snapshot(inv);}
}
