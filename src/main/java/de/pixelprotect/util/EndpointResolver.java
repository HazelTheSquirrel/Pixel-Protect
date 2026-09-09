package de.pixelprotect.util;

import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import org.bukkit.Location;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.HopperMinecart;
import org.bukkit.entity.StorageMinecart;

public final class EndpointResolver {
    private EndpointResolver() {}

    public static Endpoint resolve(Inventory inventory) {
        if (inventory == null) return null;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof HumanEntity human) return Endpoint.player(human.getUniqueId(), human.getName());
        if (holder instanceof BlockInventoryHolder blockHolder) {
            var block = blockHolder.getBlock();
            EndpointType type = block.getType().name().equals("HOPPER") ? EndpointType.HOPPER : EndpointType.CONTAINER;
            return Endpoint.block(type, block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), block.getType().getKey().toString());
        }
        if (holder instanceof DoubleChest chest) {
            Location l = chest.getLocation();
            return Endpoint.block(EndpointType.CONTAINER, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), "double_chest");
        }
        if (holder instanceof HopperMinecart minecart) {
            Location l = minecart.getLocation();
            return Endpoint.entity(EndpointType.MINECART, minecart.getUniqueId(), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), "hopper_minecart");
        }
        if (holder instanceof StorageMinecart minecart) {
            Location l = minecart.getLocation();
            return Endpoint.entity(EndpointType.MINECART, minecart.getUniqueId(), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), "storage_minecart");
        }
        if (holder instanceof org.bukkit.entity.Entity entity) {
            Location l = entity.getLocation();
            return Endpoint.entity(EndpointType.UNKNOWN, entity.getUniqueId(), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), entity.getType().name().toLowerCase());
        }
        return null;
    }
}
