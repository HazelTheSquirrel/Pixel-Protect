package de.pixelprotect.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;

import java.util.Base64;
import java.util.UUID;

/** Immutable entity forensic snapshot using Paper 26.2 public APIs. */
public record EntitySnapshot(UUID uuid, String type, String snapshot, double x, double y, double z, float yaw, float pitch,
                             double velocityX, double velocityY, double velocityZ, int fireTicks, int freezeTicks,
                             int ticksLived, boolean glowing, boolean invisible, boolean invulnerable, boolean silent,
                             boolean gravity, boolean persistent, String name, String itemData, String cause, String actor) {
    private static final Gson GSON = new Gson();
    private static final GsonComponentSerializer COMPONENTS = GsonComponentSerializer.gson();

    public EntitySnapshot(UUID uuid, String type, String snapshot, double x, double y, double z, float yaw, float pitch,
                          double velocityX, double velocityY, double velocityZ, int fireTicks, int freezeTicks, int ticksLived,
                          boolean glowing, boolean invisible, boolean invulnerable, boolean silent, boolean gravity,
                          boolean persistent, String name, String itemData) {
        this(uuid, type, snapshot, x, y, z, yaw, pitch, velocityX, velocityY, velocityZ, fireTicks, freezeTicks,
                ticksLived, glowing, invisible, invulnerable, silent, gravity, persistent, name, itemData, null, null);
    }

    public EntitySnapshot(UUID uuid, String type, String snapshot) {
        this(uuid, type, snapshot, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                false, false, false, false, true, true, null, null, null, null);
    }

    public static EntitySnapshot capture(Entity entity) {
        return capture(entity, null, null);
    }

    /**
     * Captures defensively because removal/death events can expose entities whose mutable state is already
     * partially torn down. A forensic record must never be allowed to break the originating server event.
     */
    public static EntitySnapshot capture(Entity entity, String cause, String actor) {
        if (entity == null) return null;

        String snap = null;
        try {
            var paper = entity.createSnapshot();
            snap = paper == null ? null : paper.getAsString();
        } catch (RuntimeException ignored) {
            // Some entity implementations can reject snapshot creation during teardown.
        }

        Location location = safeLocation(entity);
        Vector velocity = safeVelocity(entity);
        UUID uuid = safeUuid(entity);
        String type = safeType(entity);
        String name = safeName(entity);
        String item = safeItemData(entity);

        return new EntitySnapshot(uuid, type, snap,
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(),
                velocity.getX(), velocity.getY(), velocity.getZ(),
                safeInt(() -> entity.getFireTicks()), safeInt(() -> entity.getFreezeTicks()), safeInt(() -> entity.getTicksLived()),
                safeBoolean(entity::isGlowing), safeBoolean(entity::isInvisible), safeBoolean(entity::isInvulnerable),
                safeBoolean(entity::isSilent), safeBoolean(entity::hasGravity), safeBoolean(entity::isPersistent),
                name, item, cause, actor);
    }

    private static Location safeLocation(Entity entity) {
        try {
            Location location = entity.getLocation();
            return location == null ? new Location(null, 0, 0, 0) : location;
        } catch (RuntimeException ignored) {
            return new Location(null, 0, 0, 0);
        }
    }

    private static Vector safeVelocity(Entity entity) {
        try {
            Vector velocity = entity.getVelocity();
            return velocity == null ? new Vector() : velocity;
        } catch (RuntimeException ignored) {
            return new Vector();
        }
    }

    private static UUID safeUuid(Entity entity) {
        try {
            return entity.getUniqueId();
        } catch (RuntimeException ignored) {
            return new UUID(0L, 0L);
        }
    }

    private static String safeType(Entity entity) {
        try {
            EntityType type = entity.getType();
            return type == null || type.getKey() == null ? "minecraft:unknown" : type.getKey().toString();
        } catch (RuntimeException ignored) {
            return "minecraft:unknown";
        }
    }

    private static String safeName(Entity entity) {
        try {
            var customName = entity.customName();
            return customName == null ? null : COMPONENTS.serialize(customName);
        } catch (RuntimeException ignored) {
            try {
                String fallback = entity.getName();
                return fallback == null || fallback.isBlank() ? null : GSON.toJson(fallback);
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
    }

    private static String safeItemData(Entity entity) {
        if (!(entity instanceof org.bukkit.entity.Item itemEntity)) return null;
        try {
            var stack = itemEntity.getItemStack();
            if (stack == null || stack.isEmpty()) return null;
            return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int safeInt(IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean safeBoolean(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @FunctionalInterface
    private interface IntSupplier { int getAsInt(); }

    @FunctionalInterface
    private interface BooleanSupplier { boolean getAsBoolean(); }

    public String serialize() {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid == null ? null : uuid.toString());
        o.addProperty("type", type);
        o.addProperty("snapshot", snapshot);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("yaw", yaw);
        o.addProperty("pitch", pitch);
        o.addProperty("vx", velocityX);
        o.addProperty("vy", velocityY);
        o.addProperty("vz", velocityZ);
        o.addProperty("fire", fireTicks);
        o.addProperty("freeze", freezeTicks);
        o.addProperty("ticks", ticksLived);
        o.addProperty("glowing", glowing);
        o.addProperty("invisible", invisible);
        o.addProperty("invulnerable", invulnerable);
        o.addProperty("silent", silent);
        o.addProperty("gravity", gravity);
        o.addProperty("persistent", persistent);
        if (name != null) o.addProperty("name", name);
        if (itemData != null) o.addProperty("item", itemData);
        if (cause != null) o.addProperty("cause", cause);
        if (actor != null) o.addProperty("actor", actor);
        return GSON.toJson(o);
    }

    public static EntitySnapshot parse(String data) {
        if (data == null || data.isBlank() || data.equals("minecraft:air")) return null;
        try {
            JsonObject o = JsonParser.parseString(data).getAsJsonObject();
            if (!o.has("uuid") || !o.has("type")) return legacy(data);
            return new EntitySnapshot(
                    UUID.fromString(o.get("uuid").getAsString()), o.get("type").getAsString(), string(o, "snapshot"),
                    number(o, "x"), number(o, "y"), number(o, "z"), (float) number(o, "yaw"), (float) number(o, "pitch"),
                    number(o, "vx"), number(o, "vy"), number(o, "vz"), integer(o, "fire"), integer(o, "freeze"), integer(o, "ticks"),
                    bool(o, "glowing"), bool(o, "invisible"), bool(o, "invulnerable"), bool(o, "silent"), bool(o, "gravity"), bool(o, "persistent"),
                    string(o, "name"), string(o, "item"), string(o, "cause"), string(o, "actor"));
        } catch (RuntimeException ignored) {
            return legacy(data);
        }
    }

    private static EntitySnapshot legacy(String data) {
        if (!data.startsWith("pixelprotect:entity;")) return null;
        UUID uuid = null;
        String type = null;
        String name = null;
        for (String part : data.substring("pixelprotect:entity;".length()).split(";")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            String k = part.substring(0, i), v = part.substring(i + 1);
            if (k.equals("uuid")) {
                try { uuid = UUID.fromString(v); } catch (IllegalArgumentException ignored) { }
            } else if (k.equals("type")) type = v;
            else if (k.equals("name")) name = v;
        }
        return uuid == null || type == null ? null : new EntitySnapshot(uuid, type, null, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, false, false, false, false, true, true, name, null, null, null);
    }

    public Location location(org.bukkit.World world) { return new Location(world, x, y, z, yaw, pitch); }

    public EntityType entityType() {
        try {
            return EntityType.fromName(type.substring(type.indexOf(':') + 1));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public String plainName() {
        if (name == null) return null;
        try {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().deserialize(name).content();
        } catch (RuntimeException ignored) {
            return name;
        }
    }

    public net.kyori.adventure.text.Component nameComponent() {
        if (name == null) return null;
        try { return COMPONENTS.deserialize(name); } catch (RuntimeException ignored) { return null; }
    }

    private static String string(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
    private static double number(JsonObject o, String k) { return o.has(k) ? o.get(k).getAsDouble() : 0D; }
    private static int integer(JsonObject o, String k) { return o.has(k) ? o.get(k).getAsInt() : 0; }
    private static boolean bool(JsonObject o, String k) { return o.has(k) && o.get(k).getAsBoolean(); }
}
