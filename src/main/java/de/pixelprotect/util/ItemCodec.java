package de.pixelprotect.util;

import com.google.gson.Gson;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class ItemCodec {
    private final Gson gson;
    public ItemCodec(Gson gson){this.gson=gson;}
    public String encode(ItemStack stack){return Base64.getEncoder().encodeToString(stack.serializeAsBytes());}
    public ItemStack decode(String encoded){return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));}
    public String key(ItemStack stack){ItemStack one=stack.clone();one.setAmount(1);try{byte[] d=MessageDigest.getInstance("SHA-256").digest(one.serializeAsBytes());StringBuilder b=new StringBuilder(96).append(stack.getType().getKey()).append(':');for(byte v:d)b.append(String.format("%02x",v));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    public String materialKey(ItemStack stack){return stack.getType().getKey().toString();}
    public String json(ItemStack stack){return gson.toJson(stack.serialize());}
    public String snapshot(Inventory inventory){return snapshot(inventory.getContents());}
    public String snapshot(ItemStack[] contents){StringBuilder b=new StringBuilder(contents.length*64);for(int i=0;i<contents.length;i++){if(i>0)b.append('.');ItemStack s=contents[i];b.append(s==null||s.getType().isAir()?"-":encode(s));}return b.toString();}
    public String hashSnapshot(String snapshot){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder(64);for(byte v:d)b.append(String.format("%02x",v));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    public ItemStack[] decodeSnapshot(String snapshot){String[] v=snapshot.split("\\.",-1);ItemStack[] r=new ItemStack[v.length];for(int i=0;i<v.length;i++)r[i]=v[i].equals("-")?null:decode(v[i]);return r;}
    public void restore(Inventory inventory,String snapshot){ItemStack[] c=decodeSnapshot(snapshot);if(c.length!=inventory.getSize())throw new IllegalArgumentException("Inventory snapshot size mismatch");inventory.setContents(c);}
}
