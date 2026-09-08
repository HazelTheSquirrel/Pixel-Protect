package de.pixelprotect.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Sign;
import org.bukkit.block.SignSide;
import org.bukkit.block.Side;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Immutable block snapshot using Paper 26.2 public APIs. */
public record BlockSnapshot(String blockData, byte[] inventory, String blockEntity) {
    private static final Gson GSON = new Gson();
    private static final GsonComponentSerializer COMPONENTS = GsonComponentSerializer.gson();

    public BlockSnapshot(String blockData, byte[] inventory) { this(blockData, inventory, null); }
    public static BlockSnapshot capture(Block block) { return fromState(block.getState(), block.getBlockData()); }
    public static BlockSnapshot fromState(BlockState state) { return fromState(state, state.getBlockData()); }

    public static BlockSnapshot fromState(BlockState state, BlockData data) {
        byte[] inventory = state instanceof InventoryHolder holder
                ? ItemStack.serializeItemsAsBytes(holder.getInventory().getContents()) : null;
        return new BlockSnapshot(data.getAsString(), inventory, serializeBlockEntity(state));
    }

    private static String serializeBlockEntity(BlockState state) {
        JsonObject o = new JsonObject();
        if (state instanceof Sign sign) {
            o.addProperty("kind", "sign");
            o.addProperty("waxed", sign.isWaxed());
            o.add("front", serializeSide(sign.getSide(Side.FRONT)));
            o.add("back", serializeSide(sign.getSide(Side.BACK)));
        } else if (state instanceof Skull skull) {
            o.addProperty("kind", "skull");
            var profile = skull.getProfile();
            o.addProperty("profile", profile == null ? null : profile.toString());
            o.addProperty("noteBlockSound", skull.getNoteBlockSound() == null ? null : skull.getNoteBlockSound().toString());
        } else if (state instanceof CreatureSpawner spawner) {
            o.addProperty("kind", "spawner");
            o.addProperty("spawnedType", spawner.getSpawnedType() == null ? null : spawner.getSpawnedType().getKey().toString());
            o.addProperty("delay", spawner.getDelay());
            o.addProperty("minDelay", spawner.getMinSpawnDelay());
            o.addProperty("maxDelay", spawner.getMaxSpawnDelay());
            o.addProperty("maxNearby", spawner.getMaxNearbyEntities());
            o.addProperty("requiredRange", spawner.getRequiredPlayerRange());
            o.addProperty("spawnRange", spawner.getSpawnRange());
            o.addProperty("spawnCount", spawner.getSpawnCount());
        } else if (state instanceof Container) {
            // Inventory is stored separately in the snapshot; no duplicate block-entity payload is needed.
            return null;
        }
        return o.isEmpty() ? null : GSON.toJson(o);
    }

    private static JsonObject serializeSide(SignSide side) {
        JsonObject o = new JsonObject();
        JsonArray lines = new JsonArray();
        for (Component line : side.lines()) lines.add(COMPONENTS.serialize(line));
        o.add("lines", lines);
        o.addProperty("glowing", side.isGlowingText());
        o.addProperty("color", side.getColor().name());
        return o;
    }

    public static boolean matchesBlockEntity(BlockState state, String expected) {
        if (expected == null) return true;
        String actual = serializeBlockEntity(state);
        if (actual == null) return false;
        try { return JsonParser.parseString(actual).equals(JsonParser.parseString(expected)); }
        catch (RuntimeException ignored) { return actual.equals(expected); }
    }

    public static void applyBlockEntity(BlockState state, String data) {
        if (data == null) return;
        try {
            JsonObject o = JsonParser.parseString(data).getAsJsonObject();
            String kind = o.get("kind").getAsString();
            if (state instanceof Sign sign && kind.equals("sign")) {
                applySide(sign.getSide(Side.FRONT), o.getAsJsonObject("front"));
                applySide(sign.getSide(Side.BACK), o.getAsJsonObject("back"));
                sign.setWaxed(o.get("waxed").getAsBoolean());
                sign.update(true, false);
            } else if (state instanceof CreatureSpawner spawner && kind.equals("spawner")) {
                if (o.has("spawnedType") && !o.get("spawnedType").isJsonNull()) {
                    var type = org.bukkit.Registry.ENTITY_TYPE.get(org.bukkit.NamespacedKey.fromString(o.get("spawnedType").getAsString()));
                    spawner.setSpawnedType(type);
                } else spawner.setSpawnedType(null);
                spawner.setDelay(o.get("delay").getAsInt());
                spawner.setMinSpawnDelay(o.get("minDelay").getAsInt());
                spawner.setMaxSpawnDelay(o.get("maxDelay").getAsInt());
                spawner.setMaxNearbyEntities(o.get("maxNearby").getAsInt());
                spawner.setRequiredPlayerRange(o.get("requiredRange").getAsInt());
                spawner.setSpawnRange(o.get("spawnRange").getAsInt());
                spawner.setSpawnCount(o.get("spawnCount").getAsInt());
                spawner.update(true, false);
            }
        } catch (RuntimeException ignored) { }
    }

    private static void applySide(SignSide side, JsonObject json) {
        JsonArray lines = json.getAsJsonArray("lines");
        for (int i = 0; i < 4; i++) {
            Component component = i < lines.size() ? COMPONENTS.deserialize(lines.get(i).getAsString()) : Component.empty();
            side.line(i, component);
        }
        side.setGlowingText(json.get("glowing").getAsBoolean());
        side.setColor(org.bukkit.DyeColor.valueOf(json.get("color").getAsString()));
    }
}
