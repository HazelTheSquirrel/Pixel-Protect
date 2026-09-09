package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;
import de.pixelprotect.util.EndpointResolver;
import de.pixelprotect.util.ForensicGuard;
import de.pixelprotect.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.UUID;

public final class WorldAuditService {
    private final DatabaseManager database;private final AsyncLogQueue queue;private final OwnershipService ownership;private final ItemCodec codec;
    public WorldAuditService(JavaPlugin plugin,DatabaseManager database,AsyncLogQueue queue,OwnershipService ownership){this.database=database;this.queue=queue;this.ownership=ownership;this.codec=new ItemCodec(new com.google.gson.GsonBuilder().disableHtmlEscaping().create());}
    public void blockPlace(BlockPlaceEvent e){if(ForensicGuard.active())return;Player p=e.getPlayer();Block b=e.getBlockPlaced();String before=e.getBlockReplacedState().getBlockData().getAsString(),after=b.getBlockData().getAsString();log(p,b,before,after,inventorySnapshot(e.getBlockReplacedState()),inventorySnapshot(b.getState()),"PLACE",b.getType());if(b.getState() instanceof InventoryHolder h)ownership.record(EndpointResolver.resolve(h.getInventory()),new Owner(p.getUniqueId(),p.getName()));}
    public void blockBreak(BlockBreakEvent e){if(ForensicGuard.active())return;Player p=e.getPlayer();Block b=e.getBlock();String before=b.getBlockData().getAsString(),after=Bukkit.createBlockData(Material.AIR).getAsString();log(p,b,before,after,inventorySnapshot(b.getState()),null,"BREAK",b.getType());if(b.getState() instanceof InventoryHolder h)ownership.invalidate(EndpointResolver.resolve(h.getInventory()));}
    public void entityPlace(EntityPlaceEvent e){if(ForensicGuard.active())return;if(!(e.getEntity() instanceof InventoryHolder h))return;Player p=e.getPlayer();ownership.record(EndpointResolver.resolve(h.getInventory()),new Owner(p.getUniqueId(),p.getName()));}
    public void vehicleDestroy(VehicleDestroyEvent e){if(ForensicGuard.active())return;if(e.getVehicle() instanceof InventoryHolder h)ownership.invalidate(EndpointResolver.resolve(h.getInventory()));}
    private void log(Player p,Block b,String before,String after,String beforeInv,String afterInv,String action,Material type){long seq=database.nextSequence();String rid="#"+String.format("%010X",seq);queue.submit(new BlockLog(Instant.now(),seq,UUID.randomUUID(),rid,p.getUniqueId(),p.getName(),b.getWorld().getName(),b.getX(),b.getY(),b.getZ(),before,after,beforeInv,afterInv,action,type.getKey().toString()));}
    private String inventorySnapshot(BlockState s){if(!(s instanceof InventoryHolder h))return null;return codec.snapshot(h.getInventory());}
}
