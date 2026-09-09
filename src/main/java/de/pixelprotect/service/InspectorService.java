package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.TransferLog;
import de.pixelprotect.util.ItemCodec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InspectorService {
    private static final String SEPARATOR="________________________________________________________________________________";
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    private final JavaPlugin plugin;private final DatabaseManager database;private final ItemCodec codec;private final Set<UUID> inspectors=ConcurrentHashMap.newKeySet();
    public InspectorService(JavaPlugin plugin,DatabaseManager database,ItemCodec codec){this.plugin=plugin;this.database=database;this.codec=codec;}
    public boolean toggle(Player player){if(!inspectors.add(player.getUniqueId())){inspectors.remove(player.getUniqueId());return false;}return true;}
    public void interact(PlayerInteractEvent event){Player p=event.getPlayer();if(!inspectors.contains(p.getUniqueId()))return;if(event.getAction()!=Action.LEFT_CLICK_BLOCK&&event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;Block block=event.getClickedBlock();if(block==null)return;event.setCancelled(true);String world=block.getWorld().getName();int x=block.getX(),y=block.getY(),z=block.getZ();Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{try{Instant since=Instant.now().minusSeconds(3650L*86400L);List<DatabaseManager.StoredTransfer>transfers=database.findTransfers(world,x,y,z,2.0,since,200);List<DatabaseManager.StoredBlock>blocks=database.findBlocks(world,x,y,z,2.0,since,200);Bukkit.getScheduler().runTask(plugin,()->send(p,block,transfers,blocks));}catch(Exception e){Bukkit.getScheduler().runTask(plugin,()->p.sendMessage(Component.text(SEPARATOR+"\nPixel-Protect: Forensik-Abfrage fehlgeschlagen.\n"+SEPARATOR,NamedTextColor.RED)));}});}
    private void send(Player p,Block block,List<DatabaseManager.StoredTransfer> transfers,List<DatabaseManager.StoredBlock> blocks){Map<String,List<DatabaseManager.StoredTransfer>>groups=new LinkedHashMap<>();transfers.stream().filter(t->t.rollbackState()==null||t.rollbackState().equals("ACTIVE")).sorted(Comparator.comparingLong(DatabaseManager.StoredTransfer::sequence).reversed()).forEach(t->groups.computeIfAbsent(t.rollbackId(),k->new ArrayList<>()).add(t));if(groups.isEmpty()&&blocks.isEmpty()){p.sendMessage(Component.text(SEPARATOR+"\nKeine Forensik-Einträge an dieser Position.\n"+SEPARATOR,NamedTextColor.GRAY));return;}for(List<DatabaseManager.StoredTransfer> group:groups.values())sendTransfer(p,block,group);for(DatabaseManager.StoredBlock b:blocks)sendBlock(p,b);}
    private void sendTransfer(Player p,Block block,List<DatabaseManager.StoredTransfer>rows){DatabaseManager.StoredTransfer first=rows.getFirst();Endpoint clicked=Endpoint.block(block.getLocation(),block.getType().translationKey());List<TransferLog.ItemChange>put=new ArrayList<>(),take=new ArrayList<>();for(DatabaseManager.StoredTransfer row:rows){boolean destination=near(row.destination(),clicked);boolean source=near(row.source(),clicked);for(TransferLog.ItemChange c:row.items()){if(destination)put.add(c);if(source)take.add(c);}}Component line=Component.empty();String actor=first.attributionName()!=null&&!first.action().equals("PLAYER_TRANSFER")?first.attributionName():first.actorName();if(actor==null)actor="Unbekannt";line=line.append(Component.text(actor,NamedTextColor.GOLD)).append(Component.text(" hat ",NamedTextColor.GRAY));if(!take.isEmpty()){line=line.append(items(take,NamedTextColor.RED)).append(Component.text(" aus der Kiste entnommen",NamedTextColor.GRAY));}if(!put.isEmpty()){if(!take.isEmpty())line=line.append(Component.text(" sowie ",NamedTextColor.GRAY));line=line.append(items(put,NamedTextColor.GREEN)).append(Component.text(" in die Kiste gelegt",NamedTextColor.GRAY));}if(!first.action().equals("PLAYER_TRANSFER"))line=line.append(Component.text(" (Transportsystem)",NamedTextColor.GRAY));line=line.append(Component.text(".\nZeit: ",NamedTextColor.GRAY)).append(Component.text(TIME.format(first.timestamp()),NamedTextColor.WHITE)).append(Component.text("\nKiste: X "+block.getX()+" Y "+block.getY()+" Z "+block.getZ()+"    Rollback-ID: "+first.rollbackId(),NamedTextColor.WHITE));p.sendMessage(Component.text(SEPARATOR+"\n",NamedTextColor.GRAY).append(line).append(Component.text("\n"+SEPARATOR,NamedTextColor.GRAY)));}
    private void sendBlock(Player p,DatabaseManager.StoredBlock b){Component line=Component.text(b.playerName(),NamedTextColor.GOLD).append(Component.text(" hat "+(b.action().equals("BREAK")?"den Block entfernt":"den Block gesetzt")+".\nZeit: ",NamedTextColor.GRAY)).append(Component.text(TIME.format(b.timestamp()),NamedTextColor.WHITE)).append(Component.text("\nPosition: X "+b.x()+" Y "+b.y()+" Z "+b.z()+"    Rollback-ID: "+b.rollbackId(),NamedTextColor.WHITE));p.sendMessage(Component.text(SEPARATOR+"\n",NamedTextColor.GRAY).append(line).append(Component.text("\n"+SEPARATOR,NamedTextColor.GRAY)));}
    private Component items(List<TransferLog.ItemChange>changes,NamedTextColor color){Map<String,Integer>amounts=new LinkedHashMap<>();Map<String,String>data=new LinkedHashMap<>();for(TransferLog.ItemChange c:changes){amounts.merge(c.itemKey(),c.amount(),Integer::sum);data.putIfAbsent(c.itemKey(),c.itemData());}Component result=Component.empty();boolean first=true;for(Map.Entry<String,Integer>e:amounts.entrySet()){if(!first)result=result.append(Component.text(", ",NamedTextColor.GRAY));first=false;ItemStack stack=codec.decode(data.get(e.getKey()));result=result.append(Component.text(e.getValue()+"x ",color)).append(Component.translatable(stack.getType().translationKey()).color(color));}return result;}
    private static boolean near(Endpoint a,Endpoint b){return a!=null&&b!=null&&a.type()==b.type()&&a.world()!=null&&a.world().equals(b.world())&&a.x()==b.x()&&a.y()==b.y()&&a.z()==b.z();}
}
