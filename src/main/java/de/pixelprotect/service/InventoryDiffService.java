package de.pixelprotect.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/** Deterministic slot-level inventory diff used by audit and rollback guards. */
public final class InventoryDiffService {
    private static final Gson GSON = new Gson();

    private InventoryDiffService() {
    }

    public static String diff(ItemStack[] before, ItemStack[] after) {
        return diffEncoded(encodeAll(before), encodeAll(after));
    }

    public static boolean matches(ItemStack[] current, String expectedDiff, boolean compareAfter) {
        return matchesEncoded(encodeAll(current), expectedDiff, compareAfter);
    }

    /**
     * Pure diff operation over already encoded slot values. This keeps the algorithm independently
     * testable without bootstrapping the Bukkit/Paper runtime in a unit-test JVM.
     */
    static String diffEncoded(String[] before, String[] after) {
        JsonArray changes = new JsonArray();
        int size = Math.max(before == null ? 0 : before.length, after == null ? 0 : after.length);
        for (int slot = 0; slot < size; slot++) {
            String a = valueAt(before, slot);
            String b = valueAt(after, slot);
            if (a.equals(b)) {
                continue;
            }

            JsonObject change = new JsonObject();
            change.addProperty("slot", slot);
            change.addProperty("before", a);
            change.addProperty("after", b);
            changes.add(change);
        }

        JsonObject root = new JsonObject();
        root.add("changes", changes);
        return GSON.toJson(root);
    }

    /**
     * Pure guard operation over already encoded slot values.
     */
    static boolean matchesEncoded(String[] current, String expectedDiff, boolean compareAfter) {
        if (expectedDiff == null || expectedDiff.isBlank()) {
            return true;
        }

        try {
            JsonObject root = GSON.fromJson(expectedDiff, JsonObject.class);
            if (root == null || !root.has("changes") || !root.get("changes").isJsonArray()) {
                return false;
            }

            JsonArray changes = root.getAsJsonArray("changes");
            for (var element : changes) {
                JsonObject change = element.getAsJsonObject();
                int slot = change.get("slot").getAsInt();
                String expected = change.get(compareAfter ? "after" : "before").getAsString();
                if (!expected.equals(valueAt(current, slot))) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String[] encodeAll(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return new String[0];
        }

        String[] encoded = new String[items.length];
        for (int slot = 0; slot < items.length; slot++) {
            encoded[slot] = encode(items[slot]);
        }
        return encoded;
    }

    private static String valueAt(String[] values, int slot) {
        return values != null && slot >= 0 && slot < values.length && values[slot] != null
                ? values[slot]
                : "";
    }

    private static String encode(ItemStack stack) {
        return stack == null ? "" : Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }
}
