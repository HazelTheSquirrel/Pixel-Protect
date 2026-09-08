package de.pixelprotect.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.SignSide;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Immutable block snapshot using Paper 26.2 public APIs only. */
public record BlockSnapshot(String blockData, byte[] inventory, String blockEntity) {
    private static final Gson GSON = new Gson();
    private static final GsonComponentSerializer COMPONENTS = GsonComponentSerializer.gson();

    public BlockSnapshot(String blockData, byte[] inventory) { this(blockData, inventory, null); }

    public static BlockSnapshot capture(Block block) { return fromState(block.getState(true), block.getBlockData()); }
    public static BlockSnapshot fromState(BlockState state) { return fromState(state, state.getBlockData()); }

    public static BlockSnapshot fromState(BlockState state, BlockData data) {
        byte[] inventory = state instanceof InventoryHolder holder
                ? ItemStack.serializeItemsAsBytes(holder.getInventory().getContents())
                : null;
        return new BlockSnapshot(data.getAsString(), inventory, serializeBlockEntity(state));
    }

    private static String serializeBlockEntity(BlockState state) {
        JsonObject object = new JsonObject();
        if (state instanceof Sign sign) {
            object.addProperty("kind", "sign");
            object.addProperty("waxed", sign.isWaxed());
            object.add("front", serializeSide(sign.getSide(Side.FRONT)));
            object.add("back", serializeSide(sign.getSide(Side.BACK)));
        } else if (state instanceof Skull skull) {
            object.addProperty("kind", "skull");
            var profile = skull.getProfile();
            object.addProperty("profile", profile == null ? null : profile.toString());
            object.addProperty("noteBlockSound", skull.getNoteBlockSound() == null ? null : skull.getNoteBlockSound().toString());
        } else if (state instanceof CreatureSpawner spawner) {
            object.addProperty("kind", "spawner");
            object.addProperty("spawnedType", spawner.getSpawnedType() == null ? null : spawner.getSpawnedType().getKey().toString());
            object.addProperty("delay", spawner.getDelay());
            object.addProperty("minDelay", spawner.getMinSpawnDelay());
            object.addProperty("maxDelay", spawner.getMaxSpawnDelay());
            object.addProperty("maxNearby", spawner.getMaxNearbyEntities());
            object.addProperty("requiredRange", spawner.getRequiredPlayerRange());
            object.addProperty("spawnRange", spawner.getSpawnRange());
            object.addProperty("spawnCount", spawner.getSpawnCount());
        } else if (state instanceof Campfire campfire) {
            object.addProperty("kind", "campfire");
            JsonArray items = new JsonArray();
            JsonArray cook = new JsonArray();
            JsonArray total = new JsonArray();
            for (int i = 0; i < campfire.getSize(); i++) {
                ItemStack item = campfire.getItem(i);
                items.add(item == null ? "" : java.util.Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(new ItemStack[]{item})));
                cook.add(campfire.getCookTime(i));
                total.add(campfire.getCookTimeTotal(i));
            }
            object.add("items", items);
            object.add("cook", cook);
            object.add("total", total);
        } else if (state instanceof Container) {
            return null;
        }
        return object.isEmpty() ? null : GSON.toJson(object);
    }

    private static JsonObject serializeSide(SignSide side) {
        JsonObject object = new JsonObject();
        JsonArray lines = new JsonArray();
        for (Component line : side.lines()) lines.add(COMPONENTS.serialize(line));
        object.add("lines", lines);
        object.addProperty("glowing", side.isGlowingText());
        object.addProperty("color", side.getColor().name());
        return object;
    }

    public static boolean matchesBlockEntity(BlockState state, String expected) {
        String actual = serializeBlockEntity(state);
        if (expected == null) return actual == null;
        if (actual == null) return false;
        try {
            return JsonParser.parseString(actual).equals(JsonParser.parseString(expected));
        } catch (RuntimeException exception) {
            return actual.equals(expected);
        }
    }

    public static boolean applyBlockEntity(BlockState state, String data) {
        if (data == null) return true;
        try {
            JsonObject object = JsonParser.parseString(data).getAsJsonObject();
            String kind = object.get("kind").getAsString();
            if (state instanceof Sign sign && kind.equals("sign")) {
                applySide(sign.getSide(Side.FRONT), object.getAsJsonObject("front"));
                applySide(sign.getSide(Side.BACK), object.getAsJsonObject("back"));
                sign.setWaxed(object.get("waxed").getAsBoolean());
                return sign.update(true, false);
            }
            if (state instanceof CreatureSpawner spawner && kind.equals("spawner")) {
                if (object.has("spawnedType") && !object.get("spawnedType").isJsonNull()) {
                    var key = org.bukkit.NamespacedKey.fromString(object.get("spawnedType").getAsString());
                    var type = key == null ? null : org.bukkit.Registry.ENTITY_TYPE.get(key);
                    spawner.setSpawnedType(type);
                } else {
                    spawner.setSpawnedType(null);
                }
                spawner.setDelay(object.get("delay").getAsInt());
                spawner.setMinSpawnDelay(object.get("minDelay").getAsInt());
                spawner.setMaxSpawnDelay(object.get("maxDelay").getAsInt());
                spawner.setMaxNearbyEntities(object.get("maxNearby").getAsInt());
                spawner.setRequiredPlayerRange(object.get("requiredRange").getAsInt());
                spawner.setSpawnRange(object.get("spawnRange").getAsInt());
                spawner.setSpawnCount(object.get("spawnCount").getAsInt());
                return spawner.update(true, false);
            }
            if (state instanceof Campfire campfire && kind.equals("campfire")) {
                JsonArray items = object.getAsJsonArray("items");
                JsonArray cook = object.getAsJsonArray("cook");
                JsonArray total = object.getAsJsonArray("total");
                if (items.size() != campfire.getSize() || cook.size() != campfire.getSize() || total.size() != campfire.getSize()) return false;
                for (int i = 0; i < campfire.getSize(); i++) {
                    String encoded = items.get(i).getAsString();
                    ItemStack item = encoded.isEmpty() ? null : ItemStack.deserializeItemsFromBytes(java.util.Base64.getDecoder().decode(encoded))[0];
                    campfire.setItem(i, item);
                    campfire.setCookTime(i, cook.get(i).getAsInt());
                    campfire.setCookTimeTotal(i, total.get(i).getAsInt());
                }
                return campfire.update(true, false);
            }
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void applySide(SignSide side, JsonObject json) {
        JsonArray lines = json.getAsJsonArray("lines");
        for (int i = 0; i < 4; i++) side.line(i, i < lines.size() ? COMPONENTS.deserialize(lines.get(i).getAsString()) : Component.empty());
        side.setGlowingText(json.get("glowing").getAsBoolean());
        side.setColor(org.bukkit.DyeColor.valueOf(json.get("color").getAsString()));
    }
}
