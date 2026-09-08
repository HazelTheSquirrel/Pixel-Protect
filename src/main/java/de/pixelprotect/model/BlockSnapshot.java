package de.pixelprotect.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Immutable block state snapshot using public Bukkit/Paper APIs only. */
public record BlockSnapshot(String blockData,byte[] inventory,String blockEntity){
    private static final Gson GSON=new Gson();
    public BlockSnapshot(String blockData,byte[]inventory){this(blockData,inventory,null);}
    public static BlockSnapshot capture(Block b){return fromState(b.getState(),b.getBlockData());}
    public static BlockSnapshot fromState(BlockState s){return fromState(s,s.getBlockData());}
    public static BlockSnapshot fromState(BlockState s,BlockData d){byte[]inv=s instanceof InventoryHolder h?ItemStack.serializeItemsAsBytes(h.getInventory().getContents()):null;return new BlockSnapshot(d.getAsString(),inv,serializeBlockEntity(s));}
    private static String serializeBlockEntity(BlockState s){JsonObject o=new JsonObject();if(s instanceof Sign sign){o.addProperty("kind","sign");o.addProperty("front",String.join("\n",sign.getSide(org.bukkit.block.Side.FRONT).getLines()));o.addProperty("back",String.join("\n",sign.getSide(org.bukkit.block.Side.BACK).getLines()));}else if(s instanceof Skull skull){o.addProperty("kind","skull");if(skull.getOwningPlayer()==null)o.add("owner",com.google.gson.JsonNull.INSTANCE);else o.addProperty("owner",skull.getOwningPlayer().getUniqueId().toString());}else if(s instanceof CreatureSpawner sp){o.addProperty("kind","spawner");o.addProperty("delay",sp.getDelay());o.addProperty("minDelay",sp.getMinSpawnDelay());o.addProperty("maxDelay",sp.getMaxSpawnDelay());o.addProperty("maxNearby",sp.getMaxNearbyEntities());o.addProperty("requiredRange",sp.getRequiredPlayerRange());o.addProperty("spawnRange",sp.getSpawnRange());}else if(s instanceof Container){return null;}return o.isEmpty()?null:GSON.toJson(o);}
    public static boolean matchesBlockEntity(BlockState s,String expected){if(expected==null)return true;String actual=serializeBlockEntity(s);if(actual==null)return false;try{return JsonParser.parseString(actual).equals(JsonParser.parseString(expected));}catch(RuntimeException e){return actual.equals(expected);}}
    public static void applyBlockEntity(BlockState s,String data){if(data==null)return;try{JsonObject o=JsonParser.parseString(data).getAsJsonObject();String kind=o.get("kind").getAsString();if(s instanceof Sign sign&&kind.equals("sign")){setLines(sign.getSide(org.bukkit.block.Side.FRONT),o.get("front").getAsString());setLines(sign.getSide(org.bukkit.block.Side.BACK),o.get("back").getAsString());sign.update(true,false);}else if(s instanceof Skull skull&&kind.equals("skull")){if(o.get("owner").isJsonNull())skull.setOwningPlayer(null);else skull.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(java.util.UUID.fromString(o.get("owner").getAsString())));skull.update(true,false);}else if(s instanceof CreatureSpawner sp&&kind.equals("spawner")){sp.setDelay(o.get("delay").getAsInt());sp.setMinSpawnDelay(o.get("minDelay").getAsInt());sp.setMaxSpawnDelay(o.get("maxDelay").getAsInt());sp.setMaxNearbyEntities(o.get("maxNearby").getAsInt());sp.setRequiredPlayerRange(o.get("requiredRange").getAsInt());sp.setSpawnRange(o.get("spawnRange").getAsInt());sp.update(true,false);}}catch(RuntimeException ignored){}}
    private static void setLines(org.bukkit.block.sign.Side side,String value){String[]lines=value.split("\\n",-1);for(int i=0;i<4;i++)side.setLine(i,i<lines.length?lines[i]:"");}
}
