package de.pixelprotect.util;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

public final class ItemCodec {
    private ItemCodec() {}

    public static String encode(ItemStack stack) {
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    public static ItemStack decode(String encoded) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    }

    public static String key(ItemStack stack) {
        return stack.getType().getKey().toString();
    }
}
