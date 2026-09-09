package de.pixelprotect.util;

import com.google.gson.Gson;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class ItemCodec {
    private final Gson gson;
    public ItemCodec(Gson gson){this.gson=gson;}
    public String encode(ItemStack stack){return Base64.getEncoder().encodeToString(stack.serializeAsBytes());}
    public ItemStack decode(String encoded){return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));}
    public String key(ItemStack stack){
        ItemStack one=stack.clone();one.setAmount(1);byte[] bytes=one.serializeAsBytes();
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder(64);for(byte v:digest)b.append(String.format("%02x",v));return stack.getType().getKey()+":"+b;}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public String materialKey(ItemStack stack){return stack.getType().getKey().toString();}
    public String json(ItemStack stack){return gson.toJson(stack.serialize());}
}
