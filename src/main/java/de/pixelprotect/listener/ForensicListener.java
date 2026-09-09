package de.pixelprotect.listener;

import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import de.pixelprotect.model.Owner;
import de.pixelprotect.service.InspectorService;
import de.pixelprotect.service.OwnershipService;
import de.pixelprotect.service.TransferService;
import de.pixelprotect.service.WorldForensicsService;
import de.pixelprotect.util.EndpointResolver;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

public final class ForensicListener implements Listener {
    private final WorldForensicsService world;
    private final TransferService transfer;
    private final InspectorService inspector;
    private final OwnershipService ownership;

    public ForensicListener(WorldForensicsService world, TransferService transfer, InspectorService inspector, OwnershipService ownership) {
        this.world = world;
        this.transfer = transfer;
        this.inspector = inspector;
        this.ownership = ownership;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        world.blockBroken(event.getPlayer(), block, block.getBlockData().getAsString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent) return;
        world.blockPlaced(event.getPlayer(), event.getBlockPlaced(), event.getBlockReplacedState().getBlockData().getAsString());
        registerContainerOwner(event.getPlayer(), event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        for (BlockState state : event.getReplacedBlockStates()) {
            Block block = state.getBlock();
            world.blockPlaced(event.getPlayer(), block, state.getBlockData().getAsString());
            registerContainerOwner(event.getPlayer(), block);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!inspector.isEnabled(event.getPlayer())) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        event.setCancelled(true);
        inspector.inspect(event.getPlayer(), block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        transfer.inventoryOpen(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        transfer.inventoryClose(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        transfer.playerClick(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        transfer.playerDrag(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        transfer.automatedMove(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        transfer.groundToInventory(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickup(EntityPickupItemEvent event) {
        transfer.groundToPlayer(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        Player player = event.getPlayer();
        if (player == null) return;
        if (!(entity instanceof StorageMinecart)) return;
        var location = entity.getLocation();
        Endpoint endpoint = Endpoint.entity(EndpointType.MINECART, entity.getUniqueId(), location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), "storage_minecart");
        ownership.register(endpoint, new Owner(player.getUniqueId(), player.getName()));
    }

    private void registerContainerOwner(Player player, Block block) {
        if (!(block.getState() instanceof InventoryHolder holder)) return;
        Endpoint endpoint = EndpointResolver.resolve(holder.getInventory());
        if (endpoint != null && (endpoint.type() == EndpointType.CONTAINER || endpoint.type() == EndpointType.HOPPER)) {
            ownership.register(endpoint, new Owner(player.getUniqueId(), player.getName()));
        }
    }
}
