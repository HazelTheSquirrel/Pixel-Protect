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

/** Full Paper EntitySnapshot plus immutable spatial/runtime metadata used by rollback. */
public record EntitySnapshot(UUID uuid,String type,String snapshot,double x,double y,double z,float yaw,float pitch,double velocityX,double velocityY,double velocityZ,int fireTicks,int freezeTicks,int ticksLived,boolean glowing,boolean invisible,boolean invulnerable,boolean silent,boolean gravity,boolean persistent,String name,String itemData) {
    private static final Gson GSON=new Gson();
    private static final GsonComponentSerializer COMPONENTS=GsonComponentSerializer.gson();
    public static EntitySnapshot capture(Entity entity){var paperSnapshot=entity.createSnapshot();String snapshot=paperSnapshot==null?null:paperSnapshot.getAsString();Location location=entity.getLocation();Vector velocity=entity.getVelocity();String itemData=entity instanceof org.bukkit.entity.Item item?Base64.getEncoder().encodeToString(item.getItemStack().serializeAsBytes()):null;String name=entity.customName()==null?null:COMPONENTS.serialize(entity.customName());return new EntitySnapshot(entity.getUniqueId(),entity.getType().getKey().toString(),snapshot,location.getX(),location.getY(),location.getZ(),location.getYaw(),location.getPitch(),velocity.getX(),velocity.getY(),velocity.getZ(),entity.getFireTicks(),entity.getFreezeTicks(),entity.getTicksLived(),entity.isGlowing(),entity.isInvisible(),entity.isInvulnerable(),entity.isSilent(),entity.hasGravity(),entity.isPersistent(),name,itemData);}
    public String serialize(){JsonObject o=new JsonObject();o.addProperty("uuid",uuid.toString());o.addProperty("type",type);o.addProperty("snapshot",snapshot);o.addProperty("x",x);o.addProperty("y",y);o.addProperty("z",z);o.addProperty("yaw",yaw);o.addProperty("pitch",pitch);o.addProperty("vx",velocityX);o.addProperty("vy",velocityY);o.addProperty("vz",velocityZ);o.addProperty("fire",fireTicks);o.addProperty("freeze",freezeTicks);o.addProperty("ticks",ticksLived);o.addProperty("glowing",glowing);o.addProperty("invisible",invisible);o.addProperty("invulnerable",invulnerable);o.addProperty("silent",silent);o.addProperty("gravity",gravity);o.addProperty("persistent",persistent);if(name!=null)o.addProperty("name",name);if(itemData!=null)o.addProperty("item",itemData);return GSON.toJson(o);}
    public static EntitySnapshot parse(String data){if(data==null||data.isBlank()||data.equals("minecraft:air"))return null;try{JsonObject o=JsonParser.parseString(data).getAsJsonObject();return new EntitySnapshot(UUID.fromString(o.get("uuid").getAsString()),o.get("type").getAsString(),string(o,"snapshot"),number(o,"x"),number(o,"y"),number(o,"z"),(float)number(o,"yaw"),(float)number(o,"pitch"),number(o,"vx"),number(o,"vy"),number(o,"vz"),integer(o,"fire"),integer(o,"freeze"),integer(o,"ticks"),bool(o,"glowing"),bool(o,"invisible"),bool(o,"invulnerable"),bool(o,"silent"),bool(o,"gravity"),bool(o,"persistent"),string(o,"name"),string(o,"item"));}catch(RuntimeException ignored){return legacy(data);}}
    private static EntitySnapshot legacy(String data){if(!data.startsWith("pixelprotect:entity;"))return null;UUID uuid=null;String type=null,name=null;for(String part:data.substring("pixelprotect:entity;".length()).split(";")){int i=part.indexOf('=');if(i<0)continue;String key=part.substring(0,i),value=part.substring(i+1);if(key.equals("uuid"))try{uuid=UUID.fromString(value);}catch(IllegalArgumentException ignored){}else if(key.equals("type"))type=value;else if(key.equals("name"))name=value;}return uuid==null||type==null?null:new EntitySnapshot(uuid,type,null,0,0,0,0,0,0,0,0,0,0,0,false,false,false,false,true,true,name,null);}
    public Location location(org.bukkit.World world){return new Location(world,x,y,z,yaw,pitch);} public EntityType entityType(){try{return EntityType.fromName(type.substring(type.indexOf(':')+1));}catch(RuntimeException ignored){return null;}}
    public String plainName(){if(name==null)return null;try{return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().deserialize(name).content();}catch(RuntimeException ignored){return name;}}
    public net.kyori.adventure.text.Component nameComponent(){if(name==null)return null;try{return COMPONENTS.deserialize(name);}catch(RuntimeException ignored){return null;}}
    private static String string(JsonObject o,String key){return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():null;}private static double number(JsonObject o,String key){return o.has(key)?o.get(key).getAsDouble():0D;}private static int integer(JsonObject o,String key){return o.has(key)?o.get(key).getAsInt():0;}private static boolean bool(JsonObject o,String key){return o.has(key)&&o.get(key).getAsBoolean();}
}
