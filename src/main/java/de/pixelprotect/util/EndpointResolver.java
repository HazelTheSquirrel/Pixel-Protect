package de.pixelprotect.util;

import de.pixelprotect.model.Endpoint;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class EndpointResolver {
    private EndpointResolver() {}
    public static Endpoint resolve(Inventory inventory) {
        InventoryHolder holder=inventory.getHolder();
        if(holder instanceof Player player)return Endpoint.player(player.getUniqueId(),player.getName(),player.getLocation());
        if(holder instanceof BlockInventoryHolder bih){Block block=bih.getBlock();return Endpoint.block(block.getLocation(),block.getType().getKey().toString());}
        if(holder instanceof DoubleChest dc){
            InventoryHolder left=dc.getLeftSide();
            if(left instanceof BlockInventoryHolder bih){Block block=bih.getBlock();return Endpoint.block(block.getLocation(),"DOUBLE_CHEST");}
            return Endpoint.block(dc.getLocation(),"DOUBLE_CHEST");
        }
        if(holder instanceof Entity entity)return Endpoint.entity(entity.getUniqueId(),entity.getLocation(),entity.getType().name());
        Location location=inventory.getLocation();
        if(location!=null&&location.getWorld()!=null)return Endpoint.block(location,inventory.getType().name());
        return Endpoint.system();
    }
    public static Endpoint ground(Location location){return Endpoint.ground(location);}
}
