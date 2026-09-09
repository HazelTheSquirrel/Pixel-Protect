package de.pixelprotect.util;

import com.google.gson.Gson;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class ItemCodec {
    private final Gson gson;

    public ItemCodec(Gson gson) {
        this.gson = gson;
    }

    public String encode(ItemStack stack) {
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    public ItemStack decode(String encoded) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    }

    public String key(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        byte[] bytes = one.serializeAsBytes();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder(96);
            builder.append(stack.getType().getKey()).append(':');
            for (byte value : digest) builder.append(String.format("%02x", value));
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String materialKey(ItemStack stack) {
        return stack.getType().getKey().toString();
    }

    public String json(ItemStack stack) {
        return gson.toJson(stack.serialize());
    }

    public String snapshot(Inventory inventory) {
        StringBuilder result = new StringBuilder(inventory.getSize() * 64);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot > 0) result.append('.');
            ItemStack stack = inventory.getItem(slot);
            result.append(stack == null || stack.getType().isAir() ? "-" : encode(stack));
        }
        return result.toString();
    }

    public String hashSnapshot(String snapshot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public ItemStack[] decodeSnapshot(String snapshot) {
        String[] values = snapshot.split("\\.", -1);
        ItemStack[] result = new ItemStack[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i].equals("-") ? null : decode(values[i]);
        return result;
    }

    public void restore(Inventory inventory, String snapshot) {
        ItemStack[] contents = decodeSnapshot(snapshot);
        if (contents.length != inventory.getSize()) throw new IllegalArgumentException("Inventory snapshot size mismatch");
        inventory.setContents(contents);
    }
}
