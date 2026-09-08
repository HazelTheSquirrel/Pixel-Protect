package de.pixelprotect.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import java.util.Base64;
import java.util.UUID;

/** Small, safe entity snapshot used for deterministic audit/restore of supported entities. */
public record EntitySnapshot(UUID uuid,String type,String name,String itemData) {
    private static final Gson GSON=new Gson();
    public static EntitySnapshot capture(Entity entity){String item=null;if(entity instanceof Item itemEntity)item=Base64.getEncoder().encodeToString(ItemStack.serializeAsBytes(itemEntity.getItemStack()));return new EntitySnapshot(entity.getUniqueId(),entity.getType().getKey().toString(),entity.getName(),item);}
    public String serialize(){JsonObject o=new JsonObject();o.addProperty("uuid",uuid.toString());o.addProperty("type",type);o.addProperty("name",name);if(itemData!=null)o.addProperty("item",itemData);return GSON.toJson(o);}
    public static EntitySnapshot parse(String data){if(data==null)return null;try{JsonObject o=JsonParser.parseString(data).getAsJsonObject();return new EntitySnapshot(UUID.fromString(o.get("uuid").getAsString()),o.get("type").getAsString(),o.get("name").getAsString(),o.has("item")?o.get("item").getAsString():null);}catch(RuntimeException e){if(!data.startsWith("pixelprotect:entity;"))return null;UUID id=null;String type=null,name="entity";for(String p:data.substring("pixelprotect:entity;".length()).split(";")){int i=p.indexOf('=');if(i<0)continue;String k=p.substring(0,i),v=p.substring(i+1);if(k.equals("uuid"))try{id=UUID.fromString(v);}catch(IllegalArgumentException ignored){}else if(k.equals("type"))type=v;else if(k.equals("name"))name=v;}return id==null||type==null?null:new EntitySnapshot(id,type,name,null);}}
    public EntityType entityType(){try{return Registry.ENTITY_TYPE.get(NamespacedKey.fromString(type));}catch(RuntimeException e){return null;}}
}
