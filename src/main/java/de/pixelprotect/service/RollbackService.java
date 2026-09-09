package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import de.pixelprotect.model.TransferLog;
import de.pixelprotect.util.ForensicGuard;
import de.pixelprotect.util.ItemCodec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class RollbackService {
    private final JavaPlugin plugin;private final DatabaseManager database;private final ItemCodec codec;
    public RollbackService(JavaPlugin plugin,DatabaseManager database,ItemCodec codec){this.plugin=plugin;this.database=database;this.codec=codec;}
    public void rollback(Player player,String rawId){final String id;try{id=normalize(rawId);}catch(Exception e){player.sendMessage(Component.text("Pixel-Protect: "+e.getMessage(),NamedTextColor.RED));return;}CompletableFuture.supplyAsync(()->{try{return database.findRollbackId(id);}catch(Exception e){throw new IllegalStateException(e);}}).thenAccept(lookup->Bukkit.getScheduler().runTask(plugin,()->apply(player,lookup))).exceptionally(e->{Bukkit.getScheduler().runTask(plugin,()->player.sendMessage(Component.text("Pixel-Protect Rollback fehlgeschlagen: "+root(e).getMessage(),NamedTextColor.RED)));return null;});}

    private void apply(Player player,DatabaseManager.RollbackLookup lookup){if(lookup.transfers().isEmpty()&&lookup.blocks().isEmpty()){player.sendMessage(Component.text("Pixel-Protect: Rollback-ID "+lookup.rollbackId()+" wurde nicht gefunden.",NamedTextColor.RED));return;}List<DatabaseManager.StoredTransfer> transfers=lookup.transfers().stream().filter(t->t.rollbackState()==null||t.rollbackState().equals("ACTIVE")).toList();List<DatabaseManager.StoredBlock> blocks=lookup.blocks().stream().filter(b->b.rollbackState()==null||b.rollbackState().equals("ACTIVE")).toList();if(transfers.isEmpty()&&blocks.isEmpty()){player.sendMessage(Component.text("Pixel-Protect: Rollback-ID "+lookup.rollbackId()+" ist bereits abgeschlossen.",NamedTextColor.GRAY));return;}
        Map<String,InventoryMutation> mutations=new LinkedHashMap<>();List<DatabaseManager.StoredTransfer> groundTransfers=new ArrayList<>();
        for(DatabaseManager.StoredTransfer t:transfers){if(t.source().type()==EndpointType.GROUND||t.destination().type()==EndpointType.GROUND){groundTransfers.add(t);continue;}addMutation(mutations,t.source(),t.sourceBefore(),t.sourceAfter());addMutation(mutations,t.destination(),t.destinationBefore(),t.destinationAfter());}
        for(InventoryMutation m:mutations.values())if(!m.preflight()) {finishConflict(player,lookup,"Inventar wurde seit der protokollierten Aktion verändert.",transfers,blocks);return;}
        for(DatabaseManager.StoredBlock b:blocks){World w=Bukkit.getWorld(b.world());if(w==null||!w.isChunkLoaded(b.x()>>4,b.z()>>4)){finishConflict(player,lookup,"Welt oder Chunk der Blockänderung ist nicht verfügbar.",transfers,blocks);return;}Block target=w.getBlockAt(b.x(),b.y(),b.z());if(!target.getBlockData().getAsString().equals(b.afterData())){finishConflict(player,lookup,"Block wurde seit der protokollierten Aktion verändert.",transfers,blocks);return;}}
        List<Applied> applied=new ArrayList<>();try(ForensicGuard.Scope ignored=ForensicGuard.enter()){
            for(InventoryMutation m:mutations.values()){m.restoreBefore();applied.add(m);}
            for(DatabaseManager.StoredTransfer t:groundTransfers)restoreGround(t);
            for(DatabaseManager.StoredBlock b:blocks)restoreBlock(b);
        }catch(Exception e){try(ForensicGuard.Scope ignored=ForensicGuard.enter()){for(Applied a:applied)a.restoreAfter();}catch(Exception ignored){}finishConflict(player,lookup,"Rollback konnte nicht atomar angewendet werden.",transfers,blocks);return;}
        CompletableFuture.runAsync(()->{for(DatabaseManager.StoredTransfer t:transfers){try{database.markTransferRolledBack(t.id(),"ROLLED_BACK");}catch(Exception ignored){}}for(DatabaseManager.StoredBlock b:blocks){try{database.markBlockRolledBack(b.id(),"ROLLED_BACK");}catch(Exception ignored){}}});player.sendMessage(Component.text("________________________________________________________________________________\nPixel-Protect Rollback "+lookup.rollbackId()+" abgeschlossen. "+transfers.size()+" Transferzeilen, "+blocks.size()+" Blockänderungen zurückgesetzt.\n________________________________________________________________________________",NamedTextColor.GREEN));}

    private void finishConflict(Player p,DatabaseManager.RollbackLookup lookup,String reason,List<DatabaseManager.StoredTransfer>t,List<DatabaseManager.StoredBlock>b){CompletableFuture.runAsync(()->{for(DatabaseManager.StoredTransfer x:t)try{database.markTransferRolledBack(x.id(),"CONFLICT");}catch(Exception ignored){}for(DatabaseManager.StoredBlock x:b)try{database.markBlockRolledBack(x.id(),"CONFLICT");}catch(Exception ignored){}});p.sendMessage(Component.text("________________________________________________________________________________\nPixel-Protect Rollback "+lookup.rollbackId()+" abgebrochen: "+reason+"\nKeine Änderung wurde angewendet.\n________________________________________________________________________________",NamedTextColor.RED));}

    private void addMutation(Map<String,InventoryMutation> map,Endpoint ep,String before,String after){if(ep.type()==EndpointType.GROUND||before==null||after==null)return;String key=ep.identity();InventoryMutation existing=map.get(key);if(existing==null)map.put(key,new InventoryMutation(ep,before,after));else if(!existing.before.equals(before)||!existing.after.equals(after))throw new IllegalStateException("Inconsistent transaction snapshots for "+key);}
    private void restoreBlock(DatabaseManager.StoredBlock b){World w=Bukkit.getWorld(b.world());Block block=w.getBlockAt(b.x(),b.y(),b.z());block.setBlockData(Bukkit.createBlockData(b.beforeData()),false);if(b.beforeInventory()!=null&&block.getState() instanceof InventoryHolder h)codec.restore(h.getInventory(),b.beforeInventory());}
    private void restoreGround(DatabaseManager.StoredTransfer t){Endpoint source=t.source().type()==EndpointType.GROUND?t.source():t.destination();Endpoint destination=t.source().type()==EndpointType.GROUND?t.destination():t.source();World w=Bukkit.getWorld(source.world());if(w==null)return;Entity existing=source.entityId()==null?null:Bukkit.getEntity(source.entityId());if(destination.type()==EndpointType.GROUND){if(existing!=null)existing.remove();return;}if(existing instanceof Item item){item.setItemStack(codec.decode(t.items().getFirst().itemData()));return;}Location loc=new Location(w,source.x()+0.5,source.y()+0.25,source.z()+0.5);for(TransferLog.ItemChange c:t.items()){ItemStack stack=codec.decode(c.itemData());stack.setAmount(c.amount());w.dropItem(loc,stack);}}

    private final class InventoryMutation implements Applied{final Endpoint endpoint;final String before,after;Inventory inventory;InventoryMutation(Endpoint endpoint,String before,String after){this.endpoint=endpoint;this.before=before;this.after=after;}boolean preflight(){inventory=inventory(endpoint);return inventory!=null&&codec.hashSnapshot(codec.snapshot(inventory)).equals(codec.hashSnapshot(after));}void restoreBefore(){codec.restore(inventory,before);}void restoreAfter(){codec.restore(inventory,after);}}
    private interface Applied{void restoreAfter();}
    private Inventory inventory(Endpoint ep){return switch(ep.type()){case PLAYER->{Player p=ep.playerId()==null?null:Bukkit.getPlayer(ep.playerId());yield p==null?null:p.getInventory();}case BLOCK_CONTAINER->{World w=ep.world()==null?null:Bukkit.getWorld(ep.world());if(w==null)yield null;Block b=w.getBlockAt(ep.x(),ep.y(),ep.z());yield b.getState() instanceof InventoryHolder h?h.getInventory():null;}case MINECART->{Entity e=ep.entityId()==null?null:Bukkit.getEntity(ep.entityId());yield e instanceof InventoryHolder h?h.getInventory():null;}default->null;};}
    private static String normalize(String raw){String id=raw==null?"":raw.trim().toUpperCase(java.util.Locale.ROOT);if(!id.startsWith("#"))id="#"+id;if(!id.matches("#[0-9A-F]{10}"))throw new IllegalArgumentException("Ungültige Rollback-ID. Beispiel: #00000000A7");return id;}
    private static Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
}
