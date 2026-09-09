package de.pixelprotect.util;

import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import org.bukkit.Location;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class EndpointResolver {
    private EndpointResolver() {
    }

    public static Endpoint resolve(Inventory inventory) {
        if (inventory == null) return null;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof HumanEntity human) {
            return Endpoint.player(human.getUniqueId(), human.getName());
        }
        if (holder instanceof BlockInventoryHolder blockHolder) {
            var block = blockHolder.getBlock();
            EndpointType type = block.getType().name().equals("HOPPER") ? EndpointType.HOPPER : EndpointType.CONTAINER;
            return Endpoint.block(type, block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), block.getType().getKey().toString());
        }
        if (holder instanceof DoubleChest chest) {
            Location location = chest.getLocation();
            return Endpoint.block(EndpointType.CONTAINER, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), "double_chest");
        }
        if (holder instanceof HopperMinecart minecart) {
            Location location = minecart.getLocation();
            return Endpoint.entity(EndpointType.MINECART, minecart.getUniqueId(), location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), "hopper_minecart");
        }
        if (holder instanceof StorageMinecart minecart) {
            Location location = minecart.getLocation();
            return Endpoint.entity(EndpointType.MINECART, minecart.getUniqueId(), location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), "storage_minecart");
        }
        if (holder instanceof org.bukkit.entity.Entity entity) {
            Location location = entity.getLocation();
            return Endpoint.entity(EndpointType.UNKNOWN, entity.getUniqueId(), location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), entity.getType().name().toLowerCase());
        }
        return null;
    }
}
