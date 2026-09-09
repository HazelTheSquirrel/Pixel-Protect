package de.pixelprotect.listener;

import de.pixelprotect.service.InspectorService;
import de.pixelprotect.service.TransferService;
import de.pixelprotect.service.WorldAuditService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

public final class ForensicListener implements Listener {
    private final TransferService transfers;private final WorldAuditService world;private final InspectorService inspector;
    public ForensicListener(TransferService transfers,WorldAuditService world,InspectorService inspector){this.transfers=transfers;this.world=world;this.inspector=inspector;}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void place(BlockPlaceEvent e){world.blockPlace(e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void breakBlock(BlockBreakEvent e){world.blockBreak(e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void entityPlace(EntityPlaceEvent e){world.entityPlace(e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void vehicleDestroy(VehicleDestroyEvent e){world.vehicleDestroy(e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void click(InventoryClickEvent e){transfers.captureClick(e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void drag(InventoryDragEvent e){transfers.captureDrag(e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void move(InventoryMoveItemEvent e){transfers.captureAutomation(e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void pickup(InventoryPickupItemEvent e){transfers.captureHopperPickup(e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void drop(PlayerDropItemEvent e){transfers.capturePlayerDrop(e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void entityPickup(EntityPickupItemEvent e){transfers.capturePlayerPickup(e);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)public void inspect(PlayerInteractEvent e){inspector.interact(e);}
}
