package de.pixelprotect.service;

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
    private final AsyncLogQueue queue;
    private final OwnershipService ownership;
    private final ItemCodec codec;
    private final ConcurrentHashMap<String, ChainState> chains = new ConcurrentHashMap<>();

    public TransferService(JavaPlugin plugin, AsyncLogQueue queue, OwnershipService ownership, ItemCodec codec) {
        this.plugin=plugin;this.queue=queue;this.ownership=ownership;this.codec=codec;
    }

    public void capturePlayerInventoryChange(Player player, InventoryView view) {
        Inventory top=view.getTopInventory();
        Endpoint topEndpoint=EndpointResolver.resolve(top);
        if(topEndpoint.type()!=EndpointType.BLOCK_CONTAINER&&topEndpoint.type()!=EndpointType.MINECART)return;
        ItemStack[] topBefore=cloneContents(top.getContents());
        ItemStack[] bottomBefore=cloneContents(view.getBottomInventory().getContents());
        UUID transactionId=UUID.randomUUID();
        String rollbackId=rollbackId();
        Instant timestamp=Instant.now();
        long sequence=sequence();

        if (view instanceof InventoryView ignored) {
            if (player.getOpenInventory() != view) return;
        }

        if (player.getOpenInventory().getTopInventory()!=top) return;
        if (view.getCursor()!=null && !view.getCursor().getType().isAir()) {
            captureCursorAction(player,view,top,topEndpoint,topBefore,bottomBefore,transactionId,rollbackId,sequence,timestamp);
        }

        InventoryClickEvent click = null;
        if (plugin.getServer().getPluginManager() != null) {
            captureShiftClick(player,view,top,topEndpoint,topBefore,bottomBefore,transactionId,rollbackId,sequence,timestamp);
        }
    }

    public void captureClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top=event.getView().getTopInventory();
        Endpoint topEndpoint=EndpointResolver.resolve(top);
        if(topEndpoint.type()!=EndpointType.BLOCK_CONTAINER&&topEndpoint.type()!=EndpointType.MINECART)return;
        Inventory clicked=event.getClickedInventory();
        if(clicked==null)return;
        ItemStack[] topBefore=cloneContents(top.getContents());
        ItemStack[] bottomBefore=cloneContents(event.getView().getBottomInventory().getContents());
        UUID transactionId=UUID.randomUUID();String rollbackId=rollbackId();Instant timestamp=Instant.now();long sequence=sequence();
        if(clicked==top) {
            switch(event.getAction()) {
                case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> {
                    ItemStack current=event.getCurrentItem();
                    if(current!=null&&!current.getType().isAir()) {
                        int amount=event.getAction()==org.bukkit.event.inventory.InventoryAction.PICKUP_HALF?(current.getAmount()+1)/2:event.getAction()==org.bukkit.event.inventory.InventoryAction.PICKUP_ONE?1:current.getAmount();
                        ItemStack[] topAfter=cloneContents(topBefore);topAfter[event.getSlot()]=remaining(current, current.getAmount()-amount);
                        submit(player,transactionId,rollbackId,sequence,timestamp,topEndpoint,Endpoint.player(player.getUniqueId(),player.getName(),player.getLocation()),topBefore,topAfter,bottomBefore,bottomBefore,List.of(change(current,amount)),"PLAYER_TRANSFER");
                    }
                }
                case PLACE_ALL, PLACE_ONE, PLACE_SOME -> {
                    ItemStack cursor=event.getCursor();if(cursor!=null&&!cursor.getType().isAir()) {
                        int existing=top.getItem(event.getSlot())==null?0:top.getItem(event.getSlot()).getAmount();
                        int amount=event.getAction()==org.bukkit.event.inventory.InventoryAction.PLACE_ONE?1:Math.min(cursor.getAmount(), event.getAction()==org.bukkit.event.inventory.InventoryAction.PLACE_SOME?Math.max(0, cursor.getMaxStackSize()-existing):cursor.getAmount());
                        if(amount>0){ItemStack[] topAfter=cloneContents(topBefore);topAfter[event.getSlot()]=withAmount(existing==0?cursor:top.getItem(event.getSlot()),existing+amount);submit(player,transactionId,rollbackId,sequence,timestamp,Endpoint.player(player.getUniqueId(),player.getName(),player.getLocation()),topEndpoint,bottomBefore,bottomBefore,topBefore,topAfter,List.of(change(cursor,amount)),"PLAYER_TRANSFER");}
                    }
                }
                case SWAP_WITH_CURSOR -> {
                    ItemStack current=event.getCurrentItem(),cursor=event.getCursor();
                    if(current!=null&&!current.getType().isAir()&&cursor!=null&&!cursor.getType().isAir()) {
                        ItemStack[] topAfter=cloneContents(topBefore);topAfter[event.getSlot()]=cursor.clone();submit(player,transactionId,rollbackId,sequence,timestamp,topEndpoint,Endpoint.player(player.getUniqueId(),player.getName(),player.getLocation()),topBefore,topAfter,bottomBefore,bottomBefore,List.of(change(current, current.getAmount()),change(cursor,cursor.getAmount())),"PLAYER_TRANSFER");
                    } else if(current!=null&&!current.getType().isAir()) {
                        ItemStack[] topAfter=cloneContents(topBefore);topAfter[event.getSlot()]=null;submit(player,transactionId,rollbackId,sequence,timestamp,topEndpoint,Endpoint.player(player.getUniqueId(),player.getName(),player.getLocation()),topBefore,topAfter,bottomBefore,bottomBefore,List.of(change(current,current.getAmount())),"PLAYER_TRANSFER");
                    }
                }
                case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                    int hotbar=event.getHotbarButton();if(hotbar>=0){int raw=event.getView().convertSlot(event.getRawSlot());int playerSlot=hotbar;ItemStack current=event.getCurrentItem();ItemStack hot=event.getView().getBottomInventory().getItem(playerSlot);ItemStack[] ta=cloneContents(topBefore),ba=cloneContents(bottomBefore);ta[event.getSlot()]=hot==null?null:hot.clone();ba[playerSlot]=current==null?null:current.clone();List<TransferLog.ItemChange> changes=new ArrayList<>();if(current!=null&&!current.getType().isAir())changes.add(change(current,current.getAmount()));if(hot!=null&&!hot.getType().isAir())changes.add(change(hot,hot.getAmount()));if(!changes.isEmpty())submit(player,transactionId,rollbackId,sequence,timestamp,Endpoint.player(player.getUniqueId(),player.getName(),player.getLocation()),topEndpoint,bottomBefore,ba,topBefore,ta,changes,"PLAYER_TRANSFER");}
                }
                default -> scheduleVerified(player,topEndpoint,topBefore,bottomBefore,event,transactionId,rollbackId,sequence,timestamp);
            }
        } else if(event.getAction()==org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            scheduleVerified(player,topEndpoint,topBefore,bottomBefore,event,transactionId,rollbackId,sequence,timestamp);
        }
    }

    public void captureDrag(InventoryDragEvent event) {
        if(!(event.getWhoClicked() instanceof Player player))return;
        Endpoint topEndpoint=EndpointResolver.resolve(event.getView().getTopInventory());
        if(topEndpoint.type()!=EndpointType.BLOCK_CONTAINER&&topEndpoint.type()!=EndpointType.MINECART)return;
        boolean touchesTop=event.getRawSlots().stream().anyMatch(slot->slot<event.getView().getTopInventory().getSize());
        if(!touchesTop)return;
        Inventory top=event.getView().getTopInventory();Inventory bottom=event.getView().getBottomInventory();ItemStack[] tb=cloneContents(top.getContents()),bb=cloneContents(bottom.getContents()),ta=cloneContents(tb),ba=cloneContents(bb);List<TransferLog.ItemChange>changes=new ArrayList<>();
        for(Map.Entry<Integer,ItemStack> entry:event.getNewItems().entrySet()){if(entry.getKey()<top.getSize()){ItemStack old=ta[entry.getKey()];ItemStack next=entry.getValue();if(old!=null&&!old.getType().isAir()&&!same(old,next))changes.add(change(old,old.getAmount()));if(next!=null&&!next.getType().isAir()){int amount=next.getAmount()-(old==null?0:old.getAmount());if(amount>0)changes.add(change(next,amount));}ta[entry.getKey()]=next==null?null:next.clone();}}
        if(changes.isEmpty())return;submit(player,UUID.randomUUID(),rollbackId(),sequence(),Instant.now(),Endpoint.player(player.getUniqueId(),player.getName(),player.getLocation()),topEndpoint,bb,ba,tb,ta,changes,"PLAYER_TRANSFER");
    }

    private void captureCursorAction(Player player,InventoryView view,Inventory top,Endpoint topEndpoint,ItemStack[]tb,ItemStack[]bb,UUID tx,String rid,long seq,Instant time){ }
    private void captureShiftClick(Player player,InventoryView view,Inventory top,Endpoint topEndpoint,ItemStack[]tb,ItemStack[]bb,UUID tx,String rid,long seq,Instant time){ }

    private void scheduleVerified(Player player,Endpoint topEndpoint,ItemStack[]tb,ItemStack[]bb,InventoryClickEvent event,UUID tx,String rid,long seq,Instant time){
        ItemStack current=event.getCurrentItem()==null?null:event.getCurrentItem().clone();
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!player.isOnline())return;Inventory top=event.getView().getTopInventory(),bottom=event.getView().getBottomInventory();ItemStack[]ta=cloneContents(top.getContents()),ba=cloneContents(bottom.getContents());List<TransferLog.ItemChange>changes=new ArrayList<>();if(current!=null&&!current.getType().isAir())changes.add(change(current,current.getAmount()));if(!changes.isEmpty()&&!sameInventory(tb,ta)&&!sameInventory(bb,ba))submit(player,tx,rid,seq,time,Endpoint.player(player.getUniqueId(),player.getName(),player.getLocation()),topEndpoint,bb,ba,tb,ta,changes,"PLAYER_TRANSFER");
        });
    }

    public void captureAutomation(InventoryMoveItemEvent event){if(event.isCancelled())return;Inventory source=event.getSource(),destination=event.getDestination();Endpoint sourceEp=EndpointResolver.resolve(source),destEp=EndpointResolver.resolve(destination),initiatorEp=EndpointResolver.resolve(event.getInitiator());ItemStack item=event.getItem().clone();ItemStack[]sb=cloneContents(source.getContents()),db=cloneContents(destination.getContents());int before=destination.all(item.getType()).values().stream().mapToInt(ItemStack::getAmount).sum();Bukkit.getScheduler().runTask(plugin,()->{int after=destination.all(item.getType()).values().stream().mapToInt(ItemStack::getAmount).sum();int amount=Math.min(item.getAmount(),Math.max(0,after-before));if(amount<=0)return;CompletableFuture<Owner> owner=resolveOwner(initiatorEp);owner.thenAccept(o->{UUID chain=continueChain(sourceEp,destEp,codec.key(item));submit(new TransferLog(Instant.now(),sequence(),UUID.randomUUID(),chain,rollbackId(),o==null?null:o.uuid(),o==null?null:o.name(),o==null?null:o.uuid(),o==null?null:o.name(),sourceEp,destEp,List.of(change(item,amount)),codec.snapshot(source),codec.snapshot(source),codec.snapshot(destination),codec.snapshot(destination),codec.hashSnapshot(codec.snapshot(source)),codec.hashSnapshot(codec.snapshot(source)),codec.hashSnapshot(codec.snapshot(destination)),codec.hashSnapshot(codec.snapshot(destination)),"AUTOMATED_TRANSFER"));});});}

    public void captureHopperPickup(InventoryPickupItemEvent event){if(event.isCancelled())return;Item item=event.getItem();ItemStack stack=item.getItemStack().clone();Endpoint dest=EndpointResolver.resolve(event.getInventory()),source=Endpoint.ground(item.getLocation());resolveOwner(dest).thenAccept(owner->{int amount=stack.getAmount();submit(new TransferLog(Instant.now(),sequence(),UUID.randomUUID(),continueChain(source,dest,codec.key(stack)),rollbackId(),item.getThrower(),name(item.getThrower()),owner==null?null:owner.uuid(),owner==null?null:owner.name(),source,dest,List.of(change(stack,amount)),"-","-","-","-","-","-","-","-","GROUND_TO_AUTOMATION"));});}

    public void capturePlayerDrop(PlayerDropItemEvent event){Item item=event.getItemDrop();ItemStack stack=item.getItemStack().clone();Endpoint source=Endpoint.player(event.getPlayer().getUniqueId(),event.getPlayer().getName(),event.getPlayer().getLocation()),dest=Endpoint.ground(item.getLocation());submit(new TransferLog(Instant.now(),sequence(),UUID.randomUUID(),UUID.randomUUID(),rollbackId(),event.getPlayer().getUniqueId(),event.getPlayer().getName(),null,null,source,dest,List.of(change(stack,stack.getAmount())),"-","-","-","-","-","-","-","-","PLAYER_TO_GROUND"));}

    public void capturePlayerPickup(org.bukkit.event.entity.EntityPickupItemEvent event){if(!(event.getEntity() instanceof Player player))return;Item item=event.getItem();ItemStack stack=item.getItemStack().clone();Endpoint source=EndpointResolver.ground(item.getLocation()),dest=Endpoint.player(player.getUniqueId(),player.getName(),player.getLocation());submit(new TransferLog(Instant.now(),sequence(),UUID.randomUUID(),UUID.randomUUID(),rollbackId(),player.getUniqueId(),player.getName(),null,null,source,dest,List.of(change(stack,stack.getAmount())),"-","-","-","-","-","-","-","-","GROUND_TO_PLAYER"));}

    private void submit(Player p,UUID tx,String rid,long seq,Instant time,Endpoint source,Endpoint dest,ItemStack[]sb,ItemStack[]sa,ItemStack[]db,ItemStack[]da,List<TransferLog.ItemChange>changes,String action){String sBefore=codec.snapshot(asInventorySnapshot(sb));String sAfter=codec.snapshot(asInventorySnapshot(sa));String dBefore=codec.snapshot(asInventorySnapshot(db));String dAfter=codec.snapshot(asInventorySnapshot(da));submit(new TransferLog(time,seq,tx,UUID.randomUUID(),rid,p.getUniqueId(),p.getName(),null,null,source,dest,changes,sBefore,sAfter,dBefore,dAfter,codec.hashSnapshot(sBefore),codec.hashSnapshot(sAfter),codec.hashSnapshot(dBefore),codec.hashSnapshot(dAfter),action));}
    private void submit(TransferLog log){queue.submit(log);}
    private CompletableFuture<Owner> resolveOwner(Endpoint endpoint){return ownership.resolve(endpoint).handle((value,error)->value.orElse(null));}
    private UUID continueChain(Endpoint source,Endpoint destination,String itemKey){String key=source.identity()+"|"+itemKey;ChainState prior=chains.get(key);long now=System.currentTimeMillis();UUID chain=prior!=null&&now-prior.time()<15000?prior.chainId():UUID.randomUUID();chains.put(destination.identity()+"|"+itemKey,new ChainState(chain,now));return chain;}
    private String rollbackId(){long value=sequence();return "#"+String.format("%010X",value);}
    private long sequence(){return ((de.pixelprotect.database.DatabaseManager)plugin.getClass().cast(plugin).getClass().getDeclaredFields().length==0)?System.nanoTime():System.nanoTime();}
    private static TransferLog.ItemChange change(ItemStack stack,int amount){ItemStack one=stack.clone();one.setAmount(1);return new TransferLog.ItemChange(one.getType().getKey()+":"+one.hashCode(),encodeStatic(one),amount);}
    private static String encodeStatic(ItemStack stack){return java.util.Base64.getEncoder().encodeToString(stack.serializeAsBytes());}
    private static ItemStack remaining(ItemStack stack,int amount){return amount<=0?null:withAmount(stack,amount);}
    private static ItemStack withAmount(ItemStack stack,int amount){if(stack==null||amount<=0)return null;ItemStack copy=stack.clone();copy.setAmount(amount);return copy;}
    private static boolean same(ItemStack a,ItemStack b){return a==null?b==null:b!=null&&a.isSimilar(b);}
    private static boolean sameInventory(ItemStack[]a,ItemStack[]b){if(a.length!=b.length)return false;for(int i=0;i<a.length;i++)if(!same(a[i],b[i])||a[i]!=null&&b[i]!=null&&a[i].getAmount()!=b[i].getAmount())return false;return true;}
    private static ItemStack[] cloneContents(ItemStack[]in){ItemStack[]out=new ItemStack[in.length];for(int i=0;i<in.length;i++)out[i]=in[i]==null?null:in[i].clone();return out;}
    private Inventory asInventorySnapshot(ItemStack[] contents){throw new UnsupportedOperationException();}
    private String name(UUID uuid){if(uuid==null)return "Boden";Player p=Bukkit.getPlayer(uuid);return p==null?uuid.toString():p.getName();}
    private record ChainState(UUID chainId,long time){}
}
