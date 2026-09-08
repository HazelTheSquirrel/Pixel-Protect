package de.pixelprotect.service;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RollbackService {
    private static final java.util.Set<ActionType> ROLLBACKABLE = EnumSet.of(ActionType.BREAK,ActionType.PLACE,ActionType.BURN,ActionType.EXPLOSION,ActionType.PISTON,ActionType.FLUID,ActionType.GROW,ActionType.FORM,ActionType.SPREAD,ActionType.ENTITY_CHANGE,ActionType.BUCKET,ActionType.CONTAINER);
    private final Plugin plugin; private final AuditService audit; private final ConcurrentHashMap<UUID,Job> jobs=new ConcurrentHashMap<>();
    public RollbackService(Plugin plugin,AuditService audit){this.plugin=plugin;this.audit=audit;}
    public CompletableFuture<Result> preview(List<AuditEntry> e){return evaluate(e,false,null,false);}
    public CompletableFuture<Result> rollback(List<AuditEntry> e){return evaluate(e,true,null,false);}
    public CompletableFuture<JobSnapshot> start(List<AuditEntry> entries){final UUID id=UUID.randomUUID();final Job j=new Job(id,entries.size(),List.copyOf(entries));jobs.put(id,j);evaluate(entries,true,j,false).whenComplete((r,t)->{if(t!=null){j.status.set(Status.FAILED);j.error=rootMessage(t);}else if(j.cancelled.get())j.status.set(Status.CANCELLED);else j.status.set(Status.COMPLETED);j.snapshot=snapshot(j);});return CompletableFuture.completedFuture(snapshot(j));}
    public JobSnapshot status(UUID id){final Job j=jobs.get(id);return j==null?null:snapshot(j);}
    public boolean cancel(UUID id){final Job j=jobs.get(id);if(j==null||j.status.get()!=Status.RUNNING)return false;j.cancelled.set(true);return true;}
    public CompletableFuture<Result> restore(UUID id){final Job j=jobs.get(id);if(j==null)return CompletableFuture.failedFuture(new IllegalArgumentException("Rollback job not found."));if(j.status.get()!=Status.COMPLETED)return CompletableFuture.failedFuture(new IllegalStateException("Only completed rollback jobs can be restored."));return evaluate(j.appliedEntries,true,null,true);}
    private CompletableFuture<Result> evaluate(List<AuditEntry> entries,boolean mutate,Job job,boolean inverse){
        if(entries.isEmpty())return CompletableFuture.completedFuture(new Result(0,0));
        final Map<ChunkKey,List<AuditEntry>> groups=new HashMap<>();int unsupported=0;
        for(AuditEntry e:entries){if(!ROLLBACKABLE.contains(e.action())){unsupported++;continue;}groups.computeIfAbsent(new ChunkKey(e.world(),e.x()>>4,e.z()>>4),k->new ArrayList<>()).add(e);}
        final int initial=unsupported;if(job!=null){job.status.set(Status.RUNNING);job.skipped.addAndGet(initial);job.processed.addAndGet(initial);}
        if(groups.isEmpty())return CompletableFuture.completedFuture(new Result(0,initial));
        final CompletableFuture<Result> future=new CompletableFuture<>();final AtomicInteger left=new AtomicInteger(groups.size());final AtomicInteger applied=new AtomicInteger();final AtomicInteger skipped=new AtomicInteger(initial);final RegionScheduler scheduler=Bukkit.getRegionScheduler();
        for(var group:groups.entrySet()){final ChunkKey key=group.getKey();final List<AuditEntry> list=group.getValue();final var world=Bukkit.getWorld(key.world());if(world==null){skipped.addAndGet(list.size());if(job!=null){job.skipped.addAndGet(list.size());job.processed.addAndGet(list.size());}finish(future,left,applied,skipped);continue;}scheduler.run(plugin,world,key.chunkX(),key.chunkZ(),task->{int index=0;for(AuditEntry e:list){if(job!=null&&job.cancelled.get()){int n=list.size()-index;skipped.addAndGet(n);job.skipped.addAndGet(n);job.processed.addAndGet(n);break;}try{Block b=world.getBlockAt(e.x(),e.y(),e.z());String expected=inverse?e.beforeData():e.afterData();byte[] inv=inverse?e.beforeInventory():e.afterInventory();if(!b.getBlockData().getAsString().equals(expected)||!inventoryMatches(b,inv)){skipped.incrementAndGet();if(job!=null)job.skipped.incrementAndGet();}else{BlockData target=Bukkit.createBlockData(inverse?e.afterData():e.beforeData());if(mutate){audit.suppress(b);b.setBlockData(target,false);restoreInventory(b,inverse?e.afterInventory():e.beforeInventory());if(job!=null&&!inverse)job.appliedEntries.add(e);}applied.incrementAndGet();if(job!=null)job.applied.incrementAndGet();}}catch(RuntimeException ex){skipped.incrementAndGet();if(job!=null){job.skipped.incrementAndGet();job.error=rootMessage(ex);}}index++;if(job!=null)job.processed.incrementAndGet();}if(job!=null&&job.cancelled.get())job.status.set(Status.CANCELLED);finish(future,left,applied,skipped);});}
        return future;
    }
    private static boolean inventoryMatches(Block b,byte[] expected){if(expected==null)return true;var state=b.getState();return state instanceof InventoryHolder h&&Arrays.equals(expected,ItemStack.serializeItemsAsBytes(h.getInventory().getContents()));}
    private static void restoreInventory(Block b,byte[] data){if(data==null)return;var state=b.getState();if(state instanceof InventoryHolder h)h.getInventory().setContents(ItemStack.deserializeItemsFromBytes(data));}
    private static void finish(CompletableFuture<Result> f,AtomicInteger l,AtomicInteger a,AtomicInteger s){if(l.decrementAndGet()==0)f.complete(new Result(a.get(),s.get()));}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
    private JobSnapshot snapshot(Job j){return new JobSnapshot(j.id,j.status.get(),j.total,j.processed.get(),j.applied.get(),j.skipped.get(),j.error);}
    private record ChunkKey(UUID world,int chunkX,int chunkZ){}
    public record Result(int applied,int skipped){}
    public record JobSnapshot(UUID id,Status status,int total,int processed,int applied,int skipped,String error){}
    public enum Status{RUNNING,COMPLETED,CANCELLED,FAILED}
    private static final class Job{final UUID id;final int total;final List<AuditEntry> entries;final List<AuditEntry> appliedEntries=java.util.Collections.synchronizedList(new ArrayList<>());final AtomicInteger processed=new AtomicInteger();final AtomicInteger applied=new AtomicInteger();final AtomicInteger skipped=new AtomicInteger();final AtomicBoolean cancelled=new AtomicBoolean();final AtomicReference<Status> status=new AtomicReference<>(Status.RUNNING);volatile String error;volatile JobSnapshot snapshot;Job(UUID id,int total,List<AuditEntry> entries){this.id=id;this.total=total;this.entries=entries;}}
}
