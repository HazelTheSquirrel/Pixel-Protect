package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class RollbackService {
    private final JavaPlugin plugin;private final DatabaseManager database;private final ItemCodec codec;
    public RollbackService(JavaPlugin plugin,DatabaseManager database,ItemCodec codec){this.plugin=plugin;this.database=database;this.codec=codec;}
    public void rollback(Player player,double radius,String time){Duration duration=parseDuration(time);if(duration.isZero()||duration.isNegative())throw new IllegalArgumentException("Zeit muss groesser als 0 sein.");Instant since=Instant.now().minus(duration);String world=player.getWorld().getName();int x=player.getLocation().getBlockX(),y=player.getLocation().getBlockY(),z=player.getLocation().getBlockZ();player.sendMessage("Pixel-Protect: Rollback wird asynchron vorbereitet...");java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return new Result(database.findTransfers(world,x,y,z,radius,since,5000),database.findBlocks(world,x,y,z,radius,since,5000));}catch(Exception e){throw new RuntimeException(e);}}).thenAccept(result->Bukkit.getScheduler().runTask(plugin,()->apply(player,result))).exceptionally(ex->{Bukkit.getScheduler().runTask(plugin,()->player.sendMessage("Pixel-Protect Rollback fehlgeschlagen: "+root(ex).getMessage()));return null;});}
    private void apply(Player player,Result result){int transfers=0,blocks=0,conflicts=0;List<Event> events=new ArrayList<>();result.transfers().stream().filter(t->"ACTIVE".equals(t.rollbackState())).forEach(t->events.add(new TransferEvent(t)));result.blocks().stream().filter(b->"ACTIVE".equals(b.rollbackState())).forEach(b->events.add(new BlockEvent(b)));events.sort(Comparator.comparing(Event::timestamp).reversed());for(Event event:events){try{if(event instanceof TransferEvent te){if(reverse(te.value())){database.markTransferRolledBack(te.value().id(),"ROLLED_BACK");transfers++;}else{database.markTransferRolledBack(te.value().id(),"CONFLICT");conflicts++;}}else if(event instanceof BlockEvent be){if(reverseBlock(be.value())){database.markBlockRolledBack(be.value().id(),"ROLLED_BACK");blocks++;}else{database.markBlockRolledBack(be.value().id(),"CONFLICT");conflicts++;}}}catch(Exception e){try{if(event instanceof TransferEvent te)database.markTransferRolledBack(te.value().id(),"ERROR");else if(event instanceof BlockEvent be)database.markBlockRolledBack(be.value().id(),"ERROR");}catch(Exception ignored){}conflicts++;}}player.sendMessage("Pixel-Protect Rollback abgeschlossen: "+transfers+" Item-Transfers, "+blocks+" Blockaenderungen, "+conflicts+" Konflikte.");}
    private boolean reverse(DatabaseManager.StoredTransfer t){Inventory source=inventory(t.source()),destination=inventory(t.destination());if(source==null||destination==null)return false;ItemStack item=codec.decode(t.itemData());item.setAmount(t.amount());ItemStack remove=item.clone();Map<Integer,ItemStack> leftovers=destination.removeItemAnySlot(remove);int remaining=leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();if(remaining>0){destination.addItem(remove);return false;}Map<Integer,ItemStack> sourceLeft=source.addItem(item);if(!sourceLeft.isEmpty()){destination.addItem(item);return false;}return true;}
    private boolean reverseBlock(DatabaseManager.StoredBlock b){World world=Bukkit.getWorld(b.world());if(world==null)return false;Block block=world.getBlockAt(b.x(),b.y(),b.z());if(!block.getBlockData().getAsString().equals(b.afterData()))return false;try{block.setBlockData(Bukkit.createBlockData(b.beforeData()),false);return true;}catch(IllegalArgumentException ex){return false;}}
    private Inventory inventory(Endpoint e){return switch(e.type()){case PLAYER->{Player p=Bukkit.getPlayer(e.playerId());yield p==null?null:p.getInventory();}case BLOCK_CONTAINER->{World w=Bukkit.getWorld(e.world());if(w==null)yield null;Block b=w.getBlockAt(e.x(),e.y(),e.z());yield b.getState() instanceof InventoryHolder h?h.getInventory():null;}case MINECART->{Entity entity=Bukkit.getEntity(e.entityId());yield entity instanceof InventoryHolder h?h.getInventory():null;}default->null;};}
    public static Duration parseDuration(String raw){String s=raw.toLowerCase(java.util.Locale.ROOT).trim();if(s.length()<2)throw new IllegalArgumentException("Zeitformat z.B. 30m, 2h oder 1d.");long value=Long.parseLong(s.substring(0,s.length()-1));return switch(s.charAt(s.length()-1)){case 's'->Duration.ofSeconds(value);case 'm'->Duration.ofMinutes(value);case 'h'->Duration.ofHours(value);case 'd'->Duration.ofDays(value);case 'w'->Duration.ofDays(Math.multiplyExact(value,7));default->throw new IllegalArgumentException("Zeitformat z.B. 30m, 2h oder 1d.");};}
    private static Throwable root(Throwable t){while(t.getCause()!=null)t=t.getCause();return t;}
    private sealed interface Event permits TransferEvent,BlockEvent{Instant timestamp();}
    private record TransferEvent(DatabaseManager.StoredTransfer value) implements Event{public Instant timestamp(){return value.timestamp();}}
    private record BlockEvent(DatabaseManager.StoredBlock value) implements Event{public Instant timestamp(){return value.timestamp();}}
    private record Result(List<DatabaseManager.StoredTransfer> transfers,List<DatabaseManager.StoredBlock> blocks){}
}
