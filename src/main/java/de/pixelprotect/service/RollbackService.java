package de.pixelprotect.service;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.storage.Database;
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
    private final Plugin plugin; private final AuditService audit; private final Database database; private final ConcurrentHashMap<UUID,Job> jobs=new ConcurrentHashMap<>();
    public RollbackService(Plugin plugin,AuditService audit,Database database){this.plugin=plugin;this.audit=audit;this.database=database;}
    public CompletableFuture<Result> preview(List<AuditEntry> entries){return evaluate(entries,false,null,false);}
    public CompletableFuture<Result> rollback(List<AuditEntry> entries){return evaluate(entries,true,null,false);}
    public CompletableFuture<JobSnapshot> start(List<AuditEntry> entries){UUID id=UUID.randomUUID();Job job=new Job(id,entries.size(),List.copyOf(entries));jobs.put(id,job);return database.createRollbackJob(id,entries).thenCompose(x->evaluate(entries,true,job,false)).thenCompose(r->finishJob(job,r)).thenApply(x->snapshot(job)).whenComplete((s,t)->{if(t!=null){job.status.set(Status.FAILED);job.error=rootMessage(t);database.updateRollbackJob(job.id,job.status.get().name(),job.processed.get(),job.applied.get(),job.skipped.get(),job.error);}});}
    public JobSnapshot status(UUID id){Job j=jobs.get(id);return j==null?null:snapshot(j);}
    public CompletableFuture<JobSnapshot> statusAsync(UUID id){Job j=jobs.get(id);return j!=null?CompletableFuture.completedFuture(snapshot(j)):database.rollbackJob(id).thenApply(r->r==null?null:toSnapshot(r));}
    public boolean cancel(UUID id){Job j=jobs.get(id);if(j==null||j.status.get()!=Status.RUNNING)return false;j.cancelled.set(true);return true;}
    public CompletableFuture<Boolean> cancelAsync(UUID id){Job j=jobs.get(id);if(j==null)return database.rollbackJob(id).thenApply(r->r!=null&&"RUNNING".equals(r.status()));if(j.status.get()!=Status.RUNNING)return CompletableFuture.completedFuture(false);j.cancelled.set(true);return CompletableFuture.completedFuture(true);}
    public CompletableFuture<Result> restore(UUID id){Job local=jobs.get(id);CompletableFuture<Database.RollbackJobRecord> jf=local==null?database.rollbackJob(id):CompletableFuture.completedFuture(new Database.RollbackJobRecord(id,local.status.get().name(),local.total,local.processed.get(),local.applied.get(),local.skipped.get(),local.error,0L,0L));return jf.thenCompose(r->{if(r==null)return CompletableFuture.failedFuture(new IllegalArgumentException("Rollback job not found."));if(!"COMPLETED".equals(r.status()))return CompletableFuture.failedFuture(new IllegalStateException("Only completed rollback jobs can be restored."));return database.appliedRollbackEntries(id).thenCompose(entries->{if(entries.isEmpty())return CompletableFuture.completedFuture(new Result(0,0));return database.createRestore(id).thenCompose(restoreId->evaluate(entries,true,null,true).thenCompose(result->database.updateRestore(restoreId,"COMPLETED",result.applied(),result.skipped(),null).thenApply(x->result)).exceptionallyCompose(t->database.updateRestore(restoreId,"FAILED",0,0,rootMessage(t)).thenCompose(x->CompletableFuture.failedFuture(t))));});});}
    private CompletableFuture<Job> finishJob(Job job,Result result){if(job.cancelled.get())job.status.set(Status.CANCELLED);else job.status.set(Status.COMPLETED);return database.updateRollbackJob(job.id,job.status.get().name(),job.processed.get(),job.applied.get(),job.skipped.get(),job.error).thenApply(x->job);}
    private CompletableFuture<Result> evaluate(List<AuditEntry> entries,boolean mutate,Job job,boolean inverse){if(entries.isEmpty())return CompletableFuture.completedFuture(new Result(0,0));Map<ChunkKey,List<AuditEntry>> groups=new HashMap<>();int unsupported=0;for(AuditEntry e:entries){if(!ROLLBACKABLE.contains(e.action())){unsupported++;continue;}groups.computeIfAbsent(new ChunkKey(e.world(),e.x()>>4,e.z()>>4),x->new ArrayList<>()).add(e);}if(job!=null){job.status.set(Status.RUNNING);job.skipped.addAndGet(unsupported);job.processed.addAndGet(unsupported);}if(groups.isEmpty())return CompletableFuture.completedFuture(new Result(0,unsupported));CompletableFuture<Result> future=new CompletableFuture<>();AtomicInteger left=new AtomicInteger(groups.size()),applied=new AtomicInteger(),skipped=new AtomicInteger(unsupported);RegionScheduler scheduler=Bukkit.getRegionScheduler();for(var group:groups.entrySet()){ChunkKey key=group.getKey();List<AuditEntry> list=group.getValue();var world=Bukkit.getWorld(key.world());if(world==null){skipped.addAndGet(list.size());if(job!=null){job.skipped.addAndGet(list.size());job.processed.addAndGet(list.size());}finish(future,left,applied,skipped);continue;}scheduler.run(plugin,world,key.chunkX(),key.chunkZ(),task->{List<Long> newlyApplied=new ArrayList<>();int index=0;for(AuditEntry entry:list){if(job!=null&&job.cancelled.get()){int remaining=list.size()-index;skipped.addAndGet(remaining);job.skipped.addAndGet(remaining);job.processed.addAndGet(remaining);break;}try{Block block=world.getBlockAt(entry.x(),entry.y(),entry.z());String expected=inverse?entry.beforeData():entry.afterData();byte[] inventory=inverse?entry.beforeInventory():entry.afterInventory();if(!block.getBlockData().getAsString().equals(expected)||!inventoryMatches(block,inventory)){skipped.incrementAndGet();if(job!=null)job.skipped.incrementAndGet();}else{BlockData target=Bukkit.createBlockData(inverse?entry.afterData():entry.beforeData());if(mutate){audit.suppress(block);block.setBlockData(target,false);restoreInventory(block,inverse?entry.afterInventory():entry.beforeInventory());if(job!=null&&!inverse)newlyApplied.add(entry.id());}applied.incrementAndGet();if(job!=null)job.applied.incrementAndGet();}}catch(RuntimeException ex){skipped.incrementAndGet();if(job!=null){job.skipped.incrementAndGet();job.error=rootMessage(ex);}}index++;if(job!=null)job.processed.incrementAndGet();}if(job!=null&&!newlyApplied.isEmpty()){try{database.markRollbackApplied(job.id,newlyApplied).join();}catch(RuntimeException ex){job.error=rootMessage(ex);job.status.set(Status.FAILED);}}if(job!=null)database.updateRollbackJob(job.id,job.cancelled.get()?Status.CANCELLED.name():Status.RUNNING.name(),job.processed.get(),job.applied.get(),job.skipped.get(),job.error);if(job!=null&&job.cancelled.get())job.status.set(Status.CANCELLED);finish(future,left,applied,skipped);});}return future;}
    private static boolean inventoryMatches(Block b,byte[] expected){if(expected==null)return true;var state=b.getState();return state instanceof InventoryHolder h&&Arrays.equals(expected,ItemStack.serializeItemsAsBytes(h.getInventory().getContents()));}
    private static void restoreInventory(Block b,byte[] data){if(data==null)return;var state=b.getState();if(state instanceof InventoryHolder h)h.getInventory().setContents(ItemStack.deserializeItemsFromBytes(data));}
    private static void finish(CompletableFuture<Result> f,AtomicInteger left,AtomicInteger applied,AtomicInteger skipped){if(left.decrementAndGet()==0)f.complete(new Result(applied.get(),skipped.get()));}
    private JobSnapshot snapshot(Job j){return new JobSnapshot(j.id,j.status.get(),j.total,j.processed.get(),j.applied.get(),j.skipped.get(),j.error);}
    private static JobSnapshot toSnapshot(Database.RollbackJobRecord r){return new JobSnapshot(r.id(),Status.valueOf(r.status()),r.total(),r.processed(),r.applied(),r.skipped(),r.error());}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
    private record ChunkKey(UUID world,int chunkX,int chunkZ){}
    public record Result(int applied,int skipped){}
    public record JobSnapshot(UUID id,Status status,int total,int processed,int applied,int skipped,String error){}
    public enum Status{RUNNING,COMPLETED,CANCELLED,FAILED}
    private static final class Job{final UUID id;final int total;final List<AuditEntry> entries;final AtomicInteger processed=new AtomicInteger();final AtomicInteger applied=new AtomicInteger();final AtomicInteger skipped=new AtomicInteger();final AtomicBoolean cancelled=new AtomicBoolean();final AtomicReference<Status> status=new AtomicReference<>(Status.RUNNING);volatile String error;Job(UUID id,int total,List<AuditEntry> entries){this.id=id;this.total=total;this.entries=entries;}}
}
