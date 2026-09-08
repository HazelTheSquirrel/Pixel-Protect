package de.pixelprotect.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.inventory.ItemStack;

/** Deterministic slot-level inventory diff used by audit and rollback guards. */
public final class InventoryDiffService {
    private static final Gson GSON = new Gson();
    private InventoryDiffService() {}

    public static String diff(ItemStack[] before, ItemStack[] after) {
        JsonArray changes = new JsonArray();
        int size = Math.max(before == null ? 0 : before.length, after == null ? 0 : after.length);
        for (int slot = 0; slot < size; slot++) {
            ItemStack a = before != null && slot < before.length ? before[slot] : null;
            ItemStack b = after != null && slot < after.length ? after[slot] : null;
            if (same(a, b)) continue;
            JsonObject change = new JsonObject();
            change.addProperty("slot", slot);
            change.addProperty("before", encode(a));
            change.addProperty("after", encode(b));
            changes.add(change);
        }
        JsonObject root = new JsonObject();
        root.add("changes", changes);
        return GSON.toJson(root);
    }

    public static boolean matches(ItemStack[] current, String expectedDiff, boolean compareAfter) {
        if (expectedDiff == null || expectedDiff.isBlank()) return true;
        try {
            var root = GSON.fromJson(expectedDiff, JsonObject.class);
            var changes = root.getAsJsonArray("changes");
            for (var element : changes) {
                var change = element.getAsJsonObject();
                int slot = change.get("slot").getAsInt();
                String expected = change.get(compareAfter ? "after" : "before").getAsString();
                ItemStack actual = current != null && slot < current.length ? current[slot] : null;
                if (!expected.equals(encode(actual))) return false;
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a == null ? b == null : b != null && a.isSimilar(b) && a.getAmount() == b.getAmount();
    }

    private static String encode(ItemStack stack) {
        return stack == null ? "" : java.util.Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }
}
