package de.pixelprotect.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Immutable block state snapshot using only public Bukkit/Paper APIs. */
public record BlockSnapshot(String blockData, byte[] inventory, String blockEntity) {
    private static final Gson GSON = new Gson();

    public BlockSnapshot(String blockData, byte[] inventory) {
        this(blockData, inventory, null);
    }

    public static BlockSnapshot capture(Block block) {
        return fromState(block.getState(), block.getBlockData());
    }

    public static BlockSnapshot fromState(BlockState state) {
        return fromState(state, state.getBlockData());
    }

    public static BlockSnapshot fromState(BlockState state, BlockData data) {
        final byte[] inventory = state instanceof InventoryHolder holder
                ? ItemStack.serializeItemsAsBytes(holder.getInventory().getContents())
                : null;
        return new BlockSnapshot(data.getAsString(), inventory, serializeBlockEntity(state));
    }

    private static String serializeBlockEntity(BlockState state) {
        final JsonObject root = new JsonObject();
        root.addProperty("kind", state.getClass().getName());
        if (state instanceof TileState tile) {
            root.addProperty("persistentData", tile.getPersistentDataContainer().getKeys().stream()
                    .map(Object::toString).sorted().toList().toString());
        }
        if (state instanceof Sign sign) {
            root.addProperty("front", sign.getSide(org.bukkit.block.Side.FRONT).getLines().length == 0 ? "" : String.join("\n", sign.getSide(org.bukkit.block.Side.FRONT).getLines()));
            root.addProperty("back", String.join("\n", sign.getSide(org.bukkit.block.Side.BACK).getLines()));
        }
        if (state instanceof Skull skull) {
            root.addProperty("hasOwner", skull.hasOwner());
            if (skull.getOwningPlayer() != null) root.addProperty("owner", skull.getOwningPlayer().getUniqueId().toString());
        }
        if (state instanceof Container) {
            root.addProperty("container", true);
        }
        return root.size() == 1 ? null : GSON.toJson(root);
    }

    public static boolean matchesBlockEntity(BlockState state, String expected) {
        if (expected == null) return true;
        final String actual = serializeBlockEntity(state);
        if (actual == null) return false;
        try {
            return JsonParser.parseString(actual).equals(JsonParser.parseString(expected));
        } catch (RuntimeException ignored) {
            return actual.equals(expected);
        }
    }
}
