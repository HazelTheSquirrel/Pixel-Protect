package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.Plugin;
import java.util.*;

/** Region-safe event capture; multi-block events retain one transaction id across all records. */
public final class BlockAuditListener implements Listener {
    private final Plugin plugin;private final AuditService audit;private final boolean placeBreak,explosions,fire,piston,fluids,growth,entityChanges;
    public BlockAuditListener(Plugin plugin,AuditService audit,boolean placeBreak,boolean explosions,boolean fire,boolean piston,boolean fluids,boolean growth,boolean entityChanges){this.plugin=plugin;this.audit=audit;this.placeBreak=placeBreak;this.explosions=explosions;this.fire=fire;this.piston=piston;this.fluids=fluids;this.growth=growth;this.entityChanges=entityChanges;}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onBreak(BlockBreakEvent e){if(!placeBreak)return;Block b=e.getBlock();BlockSnapshot before=BlockSnapshot.capture(b);later(b,()->audit.recordPlayer(b,ActionType.BREAK,e.getPlayer(),before,BlockSnapshot.capture(b)));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onPlace(BlockPlaceEvent e){if(!placeBreak||e instanceof BlockMultiPlaceEvent)return;Block b=e.getBlockPlaced();BlockSnapshot before=BlockSnapshot.fromState(e.getBlockReplacedState());later(b,()->audit.recordPlayer(b,ActionType.PLACE,e.getPlayer(),before,BlockSnapshot.capture(b)));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onMultiPlace(BlockMultiPlaceEvent e){if(!placeBreak)return;List<Block> blocks=new ArrayList<>();List<BlockSnapshot> before=new ArrayList<>();for(var s:e.getReplacedBlockStates()){blocks.add(s.getBlock());before.add(BlockSnapshot.fromState(s));}if(blocks.isEmpty())return;UUID tx=audit.newTransaction();for(int i=0;i<blocks.size();i++){int n=i;Block b=blocks.get(i);later(b,()->audit.recordPlayer(b,ActionType.PLACE,e.getPlayer(),before.get(n),BlockSnapshot.capture(b),tx,n));}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onBurn(BlockBurnEvent e){if(!fire)return;Block b=e.getBlock();BlockSnapshot before=BlockSnapshot.capture(b);later(b,()->audit.recordEnvironment(b,ActionType.BURN,before,BlockSnapshot.capture(b)));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onBlockExplosion(BlockExplodeEvent e){if(explosions)recordMany(e.blockList(),ActionType.EXPLOSION,null);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onEntityExplosion(EntityExplodeEvent e){if(explosions)recordMany(e.blockList(),ActionType.EXPLOSION,e.getEntity());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onFade(BlockFadeEvent e){if(growth)recordAfter(e.getBlock(),ActionType.FORM,Actor.environment());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onForm(BlockFormEvent e){if(growth)recordAfter(e.getBlock(),ActionType.FORM,Actor.environment());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onGrow(BlockGrowEvent e){if(growth)recordAfter(e.getBlock(),ActionType.GROW,Actor.environment());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onSpread(BlockSpreadEvent e){if(growth)recordAfter(e.getBlock(),ActionType.SPREAD,Actor.environment());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onFertilize(BlockFertilizeEvent e){if(!growth)return;Actor a=e.getPlayer()==null?Actor.environment():new Actor(e.getPlayer().getUniqueId(),e.getPlayer().getName());recordStates(e.getBlocks(),ActionType.GROW,a);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onStructureGrow(StructureGrowEvent e){if(!growth)return;Actor a=e.getPlayer()==null?Actor.environment():new Actor(e.getPlayer().getUniqueId(),e.getPlayer().getName());recordStates(e.getBlocks(),ActionType.GROW,a);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onFluid(BlockFromToEvent e){if(!fluids||!Tag.FLUIDS.isTagged(e.getBlock().getType()))return;Block b=e.getToBlock();BlockSnapshot before=BlockSnapshot.capture(b);later(b,()->audit.recordEnvironment(b,ActionType.FLUID,before,BlockSnapshot.capture(b)));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onEntityChange(EntityChangeBlockEvent e){if(!entityChanges)return;Block b=e.getBlock();BlockSnapshot before=BlockSnapshot.capture(b);later(b,()->audit.recordEntity(b,ActionType.ENTITY_CHANGE,e.getEntity(),before,BlockSnapshot.capture(b)));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onEntityForm(EntityBlockFormEvent e){if(!entityChanges)return;Block b=e.getBlock();BlockSnapshot before=BlockSnapshot.capture(b);later(b,()->audit.recordEntity(b,ActionType.FORM,e.getEntity(),before,BlockSnapshot.capture(b)));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onPistonExtend(BlockPistonExtendEvent e){if(piston)recordPiston(e.getBlocks(),e.getDirection());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onPistonRetract(BlockPistonRetractEvent e){if(piston)recordPiston(e.getBlocks(),e.getDirection());}
    private void recordPiston(List<Block> moved,BlockFace direction){if(moved.isEmpty())return;Map<BlockKey,Block> affected=new LinkedHashMap<>();for(Block b:moved){affected.putIfAbsent(new BlockKey(b),b);affected.putIfAbsent(new BlockKey(b.getRelative(direction)),b.getRelative(direction));}List<Block> blocks=List.copyOf(affected.values());UUID tx=audit.newTransaction();List<BlockSnapshot> before=blocks.stream().map(BlockSnapshot::capture).toList();for(int i=0;i<blocks.size();i++){int n=i;Block b=blocks.get(i);later(b,()->audit.record(b,ActionType.PISTON,Actor.environment(),before.get(n),BlockSnapshot.capture(b),tx,n));}}
    private void recordMany(List<Block> input,ActionType action,org.bukkit.entity.Entity entity){if(input.isEmpty())return;Map<BlockKey,BlockSnapshot> before=new LinkedHashMap<>();Map<BlockKey,Block> blocks=new LinkedHashMap<>();for(Block b:input){BlockKey k=new BlockKey(b);if(!before.containsKey(k)){before.put(k,BlockSnapshot.capture(b));blocks.put(k,b);}}UUID tx=audit.newTransaction();int seq=0;for(var entry:blocks.entrySet()){Block b=entry.getValue();BlockSnapshot s=before.get(entry.getKey());int n=seq++;later(b,()->{if(entity==null)audit.record(b,action,Actor.environment(),s,BlockSnapshot.capture(b),tx,n);else audit.recordEntity(b,action,entity,s,BlockSnapshot.capture(b),tx,n);});}}
    private void recordStates(List<? extends org.bukkit.block.BlockState> states,ActionType action,Actor actor){if(states.isEmpty())return;UUID tx=audit.newTransaction();List<Block> blocks=states.stream().map(org.bukkit.block.BlockState::getBlock).toList();List<BlockSnapshot> before=states.stream().map(BlockSnapshot::fromState).toList();for(int i=0;i<blocks.size();i++){int n=i;Block b=blocks.get(i);later(b,()->audit.record(b,action,actor,before.get(n),BlockSnapshot.capture(b),tx,n));}}
    private void recordAfter(Block b,ActionType action,Actor actor){BlockSnapshot before=BlockSnapshot.capture(b);later(b,()->audit.record(b,action,actor,before,BlockSnapshot.capture(b)));}
    private void later(Block b,Runnable task){Bukkit.getRegionScheduler().runDelayed(plugin,b.getWorld(),b.getChunk().getX(),b.getChunk().getZ(),ignored->task.run(),1L);}
    private record BlockKey(UUID world,int x,int y,int z){BlockKey(Block b){this(b.getWorld().getUID(),b.getX(),b.getY(),b.getZ());}}
}
