package de.pixelprotect.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic slot-level inventory diff plus human-readable forensic item deltas. */
public final class InventoryDiffService {
    private static final Gson GSON = new Gson();

    private InventoryDiffService() {
    }

    public record ItemChange(ItemStack item, int amount) {
        public ItemChange {
            item = item == null ? null : item.clone();
        }

        public boolean added() {
            return amount > 0;
        }

        public boolean removed() {
            return amount < 0;
        }

        public Component displayName() {
            return item == null ? Component.text("Unbekannter Gegenstand") : item.effectiveName();
        }
    }

    public static String diff(ItemStack[] before, ItemStack[] after) {
        return diffEncoded(encodeAll(before), encodeAll(after));
    }

    public static boolean matches(ItemStack[] current, String expectedDiff, boolean compareAfter) {
        return matchesEncoded(encodeAll(current), expectedDiff, compareAfter);
    }

    /**
     * Calculates aggregate item movement. Item identity includes all item data while the stack amount
     * itself is deliberately excluded, so 10 stone removed from multiple slots is one forensic change.
     */
    public static List<ItemChange> itemChanges(ItemStack[] before, ItemStack[] after) {
        return itemChangesFromArrays(before, after);
    }

    public static List<ItemChange> itemChanges(byte[] before, byte[] after) {
        return itemChangesFromArrays(deserialize(before), deserialize(after));
    }

    public static boolean hasInventoryChanges(ItemStack[] before, ItemStack[] after) {
        return !itemChangesFromArrays(before, after).isEmpty();
    }

    public static String forensicJson(ItemStack[] before, ItemStack[] after) {
        JsonArray changes = new JsonArray();
        for (ItemChange change : itemChangesFromArrays(before, after)) {
            JsonObject item = new JsonObject();
            item.addProperty("amount", change.amount());
            item.addProperty("item", encode(change.item()));
            changes.add(item);
        }
        JsonObject root = new JsonObject();
        root.add("changes", changes);
        return GSON.toJson(root);
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

    /** Pure guard operation over already encoded slot values. */
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

    private static List<ItemChange> itemChangesFromArrays(ItemStack[] before, ItemStack[] after) {
        Map<String, Aggregate> totals = new LinkedHashMap<>();
        addAll(totals, before, -1);
        addAll(totals, after, 1);

        List<ItemChange> result = new ArrayList<>();
        for (Aggregate aggregate : totals.values()) {
            if (aggregate.amount == 0 || aggregate.sample == null) {
                continue;
            }
            result.add(new ItemChange(aggregate.sample, aggregate.amount));
        }
        return List.copyOf(result);
    }

    private static void addAll(Map<String, Aggregate> totals, ItemStack[] items, int sign) {
        if (items == null) {
            return;
        }
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty() || stack.getAmount() <= 0) {
                continue;
            }
            String key = identity(stack);
            Aggregate aggregate = totals.computeIfAbsent(key, ignored -> new Aggregate(stack.clone()));
            aggregate.amount += sign * stack.getAmount();
        }
    }

    private static String identity(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        return encode(one);
    }

    private static ItemStack[] deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new ItemStack[0];
        }
        try {
            return ItemStack.deserializeItemsFromBytes(bytes);
        } catch (RuntimeException ignored) {
            return new ItemStack[0];
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
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static final class Aggregate {
        private final ItemStack sample;
        private int amount;

        private Aggregate(ItemStack sample) {
            this.sample = sample;
        }
    }
}
