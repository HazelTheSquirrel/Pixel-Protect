package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import de.pixelprotect.model.Owner;
import de.pixelprotect.model.TransferLog;
import de.pixelprotect.util.EndpointResolver;
import de.pixelprotect.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class TransferService {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final AsyncLogQueue queue;
    private final OwnershipService ownership;
    private final ItemCodec codec;
    private final ConcurrentHashMap<String, ChainState> chains=new ConcurrentHashMap<>();

    public TransferService(JavaPlugin plugin,DatabaseManager database,AsyncLogQueue queue,OwnershipService ownership,ItemCodec codec){this.plugin=plugin;this.database=database;this.queue=queue;this.ownership=ownership;this.codec=codec;}

    public void captureClick(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player player))return;
        Inventory top=event.getView().getTopInventory();Endpoint topEp=EndpointResolver.resolve(top);if(!container(topEp))return;Inventory bottom=event.getView().getBottomInventory();
        ItemStack[] tb=cloneContents(top.getContents()),bb=cloneContents(bottom.getContents());long seq=database.nextSequence();String rid=rollbackId(seq);UUID tx=UUID.randomUUID();Instant time=Instant.now();
        if(event.getClickedInventory()==top){
            switch(event.getAction()){
                case PICKUP_ALL,PICKUP_HALF,PICKUP_ONE,PICKUP_SOME->{ItemStack current=event.getCurrentItem();if(current==null||current.getType().isAir())return;int amount=switch(event.getAction()){case PICKUP_HALF->(current.getAmount()+1)/2;case PICKUP_ONE->1;default->current.getAmount();};ItemStack[]ta=cloneContents(tb);ta[event.getSlot()]=withAmount(current,current.getAmount()-amount);submit(player,tx,rid,seq,time,topEp,playerEndpoint(player),tb,ta,bb,bb,List.of(change(current,amount)),"PLAYER_TRANSFER");}
                case PLACE_ALL,PLACE_ONE,PLACE_SOME->{ItemStack cursor=event.getCursor();if(cursor==null||cursor.getType().isAir())return;ItemStack current=event.getCurrentItem();int existing=current==null||current.getType().isAir()?0:current.getAmount();int capacity=Math.max(0,cursor.getMaxStackSize()-existing);int amount=event.getAction()==org.bukkit.event.inventory.InventoryAction.PLACE_ONE?1:event.getAction()==org.bukkit.event.inventory.InventoryAction.PLACE_SOME?Math.min(cursor.getAmount(),capacity):cursor.getAmount();if(amount<=0)return;ItemStack[]ta=cloneContents(tb);ta[event.getSlot()]=withAmount(current==null||current.getType().isAir()?cursor:current,existing+amount);submit(player,tx,rid,seq,time,playerEndpoint(player),topEp,bb,bb,tb,ta,List.of(change(cursor,amount)),"PLAYER_TRANSFER");}
                case SWAP_WITH_CURSOR->{ItemStack current=event.getCurrentItem(),cursor=event.getCursor();if(current==null||current.getType().isAir())return;ItemStack[]ta=cloneContents(tb);ta[event.getSlot()]=cursor==null||cursor.getType().isAir()?null:cursor.clone();List<TransferLog.ItemChange>changes=new ArrayList<>();changes.add(change(current,current.getAmount()));if(cursor!=null&&!cursor.getType().isAir())changes.add(change(cursor,cursor.getAmount()));submit(player,tx,rid,seq,time,topEp,playerEndpoint(player),tb,ta,bb,bb,changes,"PLAYER_TRANSFER");}
                case HOTBAR_SWAP,HOTBAR_MOVE_AND_READD->{int hotbar=event.getHotbarButton();if(hotbar<0)return;ItemStack current=event.getCurrentItem(),hot=bottom.getItem(hotbar);ItemStack[]ta=cloneContents(tb),ba=cloneContents(bb);ta[event.getSlot()]=hot==null?null:hot.clone();ba[hotbar]=current==null?null:current.clone();List<TransferLog.ItemChange>changes=new ArrayList<>();if(current!=null&&!current.getType().isAir())changes.add(change(current,current.getAmount()));if(hot!=null&&!hot.getType().isAir())changes.add(change(hot,hot.getAmount()));if(!changes.isEmpty())submit(player,tx,rid,seq,time,playerEndpoint(player),topEp,bb,ba,tb,ta,changes,"PLAYER_TRANSFER");}
                default->scheduleVerified(player,event,topEp,tb,bb,tx,rid,seq,time);
            }
        }else if(event.getAction()==org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY){scheduleVerified(player,event,topEp,tb,bb,tx,rid,seq,time);}
    }

    public void captureDrag(InventoryDragEvent event){
        if(!(event.getWhoClicked() instanceof Player player))return;Inventory top=event.getView().getTopInventory();Endpoint topEp=EndpointResolver.resolve(top);if(!container(topEp))return;if(event.getRawSlots().stream().noneMatch(s->s<top.getSize()))return;Inventory bottom=event.getView().getBottomInventory();ItemStack[]tb=cloneContents(top.getContents()),bb=cloneContents(bottom.getContents()),ta=cloneContents(tb);List<TransferLog.ItemChange>changes=new ArrayList<>();
        for(Map.Entry<Integer,ItemStack> e:event.getNewItems().entrySet()){if(e.getKey()>=top.getSize())continue;ItemStack old=ta[e.getKey()],next=e.getValue();if(old!=null&&!old.getType().isAir()&&!same(old,next))changes.add(change(old,old.getAmount()));if(next!=null&&!next.getType().isAir()){int delta=next.getAmount()-(old==null?0:old.getAmount());if(delta>0)changes.add(change(next,delta));}ta[e.getKey()]=next==null?null:next.clone();}
        if(changes.isEmpty())return;long seq=database.nextSequence();submit(player,UUID.randomUUID(),rollbackId(seq),seq,Instant.now(),playerEndpoint(player),topEp,bb,bb,tb,ta,merge(changes),"PLAYER_TRANSFER");
    }

    private void scheduleVerified(Player player,InventoryClickEvent event,Endpoint topEp,ItemStack[]tb,ItemStack[]bb,UUID tx,String rid,long seq,Instant time){ItemStack current=event.getCurrentItem()==null?null:event.getCurrentItem().clone();Bukkit.getScheduler().runTask(plugin,()->{if(!player.isOnline())return;Inventory top=event.getView().getTopInventory(),bottom=event.getView().getBottomInventory();ItemStack[]ta=cloneContents(top.getContents()),ba=cloneContents(bottom.getContents());if(sameInventory(tb,ta)&&sameInventory(bb,ba))return;List<TransferLog.ItemChange>changes=current==null?List.of():List.of(change(current,current.getAmount()));boolean topChanged=!sameInventory(tb,ta),bottomChanged=!sameInventory(bb,ba);if(topChanged&&bottomChanged)submit(player,tx,rid,seq,time,playerEndpoint(player),topEp,bb,ba,tb,ta,changes,"PLAYER_TRANSFER");});}

    public void captureAutomation(InventoryMoveItemEvent event){if(event.isCancelled())return;Inventory source=event.getSource(),dest=event.getDestination();Endpoint sourceEp=EndpointResolver.resolve(source),destEp=EndpointResolver.resolve(dest),initiatorEp=EndpointResolver.resolve(event.getInitiator());ItemStack item=event.getItem().clone();ItemStack[]sb=cloneContents(source.getContents()),db=cloneContents(dest.getContents());int before=countSimilar(dest,item);Bukkit.getScheduler().runTask(plugin,()->{int after=countSimilar(dest,item);int amount=Math.min(item.getAmount(),Math.max(0,after-before));if(amount<=0)return;ItemStack[]sa=cloneContents(source.getContents()),da=cloneContents(dest.getContents());long seq=database.nextSequence();String rid=rollbackId(seq);resolveOwner(initiatorEp).thenAccept(owner->{UUID chain=continueChain(sourceEp,destEp,codec.key(item));submit(new TransferLog(Instant.now(),seq,UUID.randomUUID(),chain,rid,owner==null?null:owner.uuid(),owner==null?null:owner.name(),owner==null?null:owner.uuid(),owner==null?null:owner.name(),sourceEp,destEp,List.of(change(item,amount)),codec.snapshot(sb),codec.snapshot(sa),codec.snapshot(db),codec.snapshot(da),codec.hashSnapshot(codec.snapshot(sb)),codec.hashSnapshot(codec.snapshot(sa)),codec.hashSnapshot(codec.snapshot(db)),codec.hashSnapshot(codec.snapshot(da)),owner==null?"AUTOMATED_TRANSFER_OWNER_UNKNOWN":"AUTOMATED_TRANSFER"));});});}

    public void captureHopperPickup(InventoryPickupItemEvent event){if(event.isCancelled())return;Item item=event.getItem();ItemStack stack=item.getItemStack().clone();Inventory dest=event.getInventory();Endpoint destEp=EndpointResolver.resolve(dest),sourceEp=Endpoint.ground(item.getLocation());ItemStack[]db=cloneContents(dest.getContents());int before=countSimilar(dest,stack);Bukkit.getScheduler().runTask(plugin,()->{int amount=Math.min(stack.getAmount(),Math.max(0,countSimilar(dest,stack)-before));if(amount<=0)return;ItemStack[]da=cloneContents(dest.getContents());long seq=database.nextSequence();resolveOwner(destEp).thenAccept(owner->submit(new TransferLog(Instant.now(),seq,UUID.randomUUID(),continueChain(sourceEp,destEp,codec.key(stack)),rollbackId(seq),item.getThrower(),name(item.getThrower()),owner==null?null:owner.uuid(),owner==null?null:owner.name(),sourceEp,destEp,List.of(change(stack,amount)),"-","-",codec.snapshot(db),codec.snapshot(da),"-","-",codec.hashSnapshot(codec.snapshot(db)),codec.hashSnapshot(codec.snapshot(da)),"GROUND_TO_AUTOMATION")));});}

    public void capturePlayerDrop(PlayerDropItemEvent event){Player p=event.getPlayer();Item item=event.getItemDrop();ItemStack stack=item.getItemStack().clone();Inventory inv=p.getInventory();ItemStack[]before=cloneContents(inv.getContents());Endpoint source=playerEndpoint(p),dest=Endpoint.ground(item.getLocation());Bukkit.getScheduler().runTask(plugin,()->{ItemStack[]after=cloneContents(inv.getContents());int delta=Math.max(0,countSimilar(before,stack)-countSimilar(after,stack));if(delta<=0)delta=stack.getAmount();long seq=database.nextSequence();submit(new TransferLog(Instant.now(),seq,UUID.randomUUID(),UUID.randomUUID(),rollbackId(seq),p.getUniqueId(),p.getName(),null,null,source,dest,List.of(change(stack,Math.min(delta,stack.getAmount()))),codec.snapshot(before),codec.snapshot(after),"-","-",codec.hashSnapshot(codec.snapshot(before)),codec.hashSnapshot(codec.snapshot(after)),"-","-","PLAYER_TO_GROUND"));});}

    public void capturePlayerPickup(org.bukkit.event.entity.EntityPickupItemEvent event){if(!(event.getEntity() instanceof Player p))return;Item item=event.getItem();ItemStack stack=item.getItemStack().clone();Inventory inv=p.getInventory();ItemStack[]before=cloneContents(inv.getContents());Endpoint source=Endpoint.ground(item.getLocation()),dest=playerEndpoint(p);Bukkit.getScheduler().runTask(plugin,()->{ItemStack[]after=cloneContents(inv.getContents());int amount=Math.min(stack.getAmount(),Math.max(0,countSimilar(after,stack)-countSimilar(before,stack)));if(amount<=0)return;long seq=database.nextSequence();submit(new TransferLog(Instant.now(),seq,UUID.randomUUID(),UUID.randomUUID(),rollbackId(seq),p.getUniqueId(),p.getName(),null,null,source,dest,List.of(change(stack,amount)),"-","-",codec.snapshot(before),codec.snapshot(after),"-","-",codec.hashSnapshot(codec.snapshot(before)),codec.hashSnapshot(codec.snapshot(after)),"GROUND_TO_PLAYER"));});}

    private void submit(Player p,UUID tx,String rid,long seq,Instant time,Endpoint source,Endpoint dest,ItemStack[]sb,ItemStack[]sa,ItemStack[]db,ItemStack[]da,List<TransferLog.ItemChange>items,String action){String s1=codec.snapshot(sb),s2=codec.snapshot(sa),d1=codec.snapshot(db),d2=codec.snapshot(da);submit(new TransferLog(time,seq,tx,UUID.randomUUID(),rid,p.getUniqueId(),p.getName(),null,null,source,dest,items,s1,s2,d1,d2,codec.hashSnapshot(s1),codec.hashSnapshot(s2),codec.hashSnapshot(d1),codec.hashSnapshot(d2),action));}
    private void submit(TransferLog log){queue.submit(log);}
    private CompletableFuture<Owner> resolveOwner(Endpoint ep){return ownership.resolve(ep).handle((v,e)->e==null?v.orElse(null):null);}
    private UUID continueChain(Endpoint source,Endpoint destination,String key){long now=System.currentTimeMillis();String k=source.identity()+"|"+key;ChainState old=chains.get(k);UUID chain=old!=null&&now-old.time<15000?old.chain:UUID.randomUUID();chains.put(destination.identity()+"|"+key,new ChainState(chain,now));return chain;}
    private String rollbackId(long seq){return "#"+String.format("%010X",seq);}
    private static TransferLog.ItemChange change(ItemStack stack,int amount){ItemStack one=stack.clone();one.setAmount(1);return new TransferLog.ItemChange(key(one),java.util.Base64.getEncoder().encodeToString(one.serializeAsBytes()),amount);}
    private static String key(ItemStack stack){return stack.getType().getKey()+":"+Integer.toHexString(java.util.Arrays.hashCode(stack.serializeAsBytes()));}
    private static ItemStack withAmount(ItemStack stack,int amount){if(stack==null||amount<=0)return null;ItemStack c=stack.clone();c.setAmount(amount);return c;}
    private static boolean same(ItemStack a,ItemStack b){return a==null?b==null:b!=null&&a.isSimilar(b)&&a.getAmount()==b.getAmount();}
    private static boolean sameInventory(ItemStack[]a,ItemStack[]b){if(a.length!=b.length)return false;for(int i=0;i<a.length;i++)if(!same(a[i],b[i]))return false;return true;}
    private static ItemStack[] cloneContents(ItemStack[]a){ItemStack[]r=new ItemStack[a.length];for(int i=0;i<a.length;i++)r[i]=a[i]==null?null:a[i].clone();return r;}
    private static int countSimilar(Inventory inv,ItemStack target){int total=0;for(ItemStack s:inv.getContents())if(s!=null&&s.isSimilar(target))total+=s.getAmount();return total;}
    private static int countSimilar(ItemStack[] inv,ItemStack target){int total=0;for(ItemStack s:inv)if(s!=null&&s.isSimilar(target))total+=s.getAmount();return total;}
    private static List<TransferLog.ItemChange> merge(List<TransferLog.ItemChange> input){List<TransferLog.ItemChange>out=new ArrayList<>();for(TransferLog.ItemChange c:input){int i=-1;for(int j=0;j<out.size();j++)if(out.get(j).itemKey().equals(c.itemKey())){i=j;break;}if(i<0)out.add(c);else{TransferLog.ItemChange old=out.get(i);out.set(i,new TransferLog.ItemChange(old.itemKey(),old.itemData(),old.amount()+c.amount()));}}return List.copyOf(out);}
    private static boolean container(Endpoint e){return e.type()==EndpointType.BLOCK_CONTAINER||e.type()==EndpointType.MINECART;}
    private static Endpoint playerEndpoint(Player p){return Endpoint.player(p.getUniqueId(),p.getName(),p.getLocation());}
    private String name(UUID id){if(id==null)return "Boden";Player p=Bukkit.getPlayer(id);return p==null?id.toString():p.getName();}
    private record ChainState(UUID chain,long time){}
}
