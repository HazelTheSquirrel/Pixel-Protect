package de.pixelprotect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.AuditQuery;
import de.pixelprotect.service.InspectService;
import de.pixelprotect.service.RollbackService;
import de.pixelprotect.storage.Database;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PixelProtectCommand {
    private final Plugin plugin; private final Database database; private final RollbackService rollback; private final InspectService inspect;
    private final int maxHours; private final int maxRadius; private final int maxRecords;
    public PixelProtectCommand(Plugin plugin, Database database, RollbackService rollback, InspectService inspect, int maxHours, int maxRadius, int maxRecords) {
        this.plugin=plugin; this.database=database; this.rollback=rollback; this.inspect=inspect; this.maxHours=maxHours; this.maxRadius=maxRadius; this.maxRecords=maxRecords;
    }
    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("pixelprotect")
            .executes(c -> version(c.getSource().getSender()))
            .then(Commands.literal("version").executes(c -> version(c.getSource().getSender())))
            .then(Commands.literal("status").requires(s -> s.getSender().hasPermission("pixelprotect.status")).executes(c -> status(c.getSource().getSender())))
            .then(Commands.literal("inspect").requires(s -> s.getSender().hasPermission("pixelprotect.inspect")).executes(c -> inspect(c.getSource().getSender())))
            .then(Commands.literal("lookup").requires(s -> s.getSender().hasPermission("pixelprotect.lookup"))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1,maxRadius)).then(Commands.argument("hours",IntegerArgumentType.integer(1,maxHours))
                    .executes(c -> lookup(c.getSource(),IntegerArgumentType.getInteger(c,"radius"),IntegerArgumentType.getInteger(c,"hours"),""))
                    .then(Commands.argument("selectors",StringArgumentType.greedyString()).executes(c -> lookup(c.getSource(),IntegerArgumentType.getInteger(c,"radius"),IntegerArgumentType.getInteger(c,"hours"),StringArgumentType.getString(c,"selectors")))))))
            .then(Commands.literal("near").requires(s -> s.getSender().hasPermission("pixelprotect.lookup"))
                .executes(c -> lookup(c.getSource(),5,1,""))
                .then(Commands.argument("selectors",StringArgumentType.greedyString()).executes(c -> lookup(c.getSource(),5,1,StringArgumentType.getString(c,"selectors")))))
            .then(Commands.literal("rollback").requires(s -> s.getSender().hasPermission("pixelprotect.rollback"))
                .then(Commands.literal("status").then(Commands.argument("job",StringArgumentType.word()).executes(c -> rollbackStatus(c.getSource().getSender(),StringArgumentType.getString(c,"job")))))
                .then(Commands.literal("cancel").then(Commands.argument("job",StringArgumentType.word()).executes(c -> rollbackCancel(c.getSource().getSender(),StringArgumentType.getString(c,"job")))))
                .then(Commands.argument("radius",IntegerArgumentType.integer(1,maxRadius)).then(Commands.argument("hours",IntegerArgumentType.integer(1,maxHours))
                    .executes(c -> rollback(c.getSource(),IntegerArgumentType.getInteger(c,"radius"),IntegerArgumentType.getInteger(c,"hours"),""))
                    .then(Commands.argument("selectors",StringArgumentType.greedyString()).executes(c -> rollback(c.getSource(),IntegerArgumentType.getInteger(c,"radius"),IntegerArgumentType.getInteger(c,"hours"),StringArgumentType.getString(c,"selectors")))))))
            .then(Commands.literal("restore").requires(s -> s.getSender().hasPermission("pixelprotect.rollback"))
                .then(Commands.argument("job",StringArgumentType.word()).executes(c -> restore(c.getSource().getSender(),StringArgumentType.getString(c,"job")))))
            .then(Commands.literal("purge").requires(s -> s.getSender().hasPermission("pixelprotect.purge"))
                .then(Commands.argument("days",IntegerArgumentType.integer(1,3650)).executes(c -> purge(c.getSource().getSender(),IntegerArgumentType.getInteger(c,"days")))))
            .then(Commands.literal("help").executes(c -> help(c.getSource().getSender())));
    }
    private int inspect(CommandSender sender){ if(!(sender instanceof Player p)){sender.sendPlainMessage("PixelProtect: inspect is only available to players.");return 0;} boolean e=inspect.toggle(p);p.sendPlainMessage(e?"PixelProtect: inspect enabled. Click a block to inspect its history.":"PixelProtect: inspect disabled.");return Command.SINGLE_SUCCESS; }
    private int lookup(CommandSourceStack source,int dr,int dh,String raw){ Location l=source.getLocation(); if(l.getWorld()==null)return message(source.getSender(),"PixelProtect: no world context available."); var p=SelectorParser.parse(tokens(raw),dr,dh,maxRadius,maxHours); if(!p.errors().isEmpty())return sendErrors(source.getSender(),p.errors()); var q=query(l,p); if(p.countOnly())database.count(q).thenAccept(n->send(source.getSender(),"PixelProtect: "+n+" matching audit record(s).")); else database.query(q).thenAccept(e->sendLookup(source.getSender(),e)); source.getSender().sendPlainMessage("PixelProtect: querying audit history..."); return Command.SINGLE_SUCCESS; }
    private int rollback(CommandSourceStack source,int dr,int dh,String raw){ Location l=source.getLocation(); if(l.getWorld()==null)return message(source.getSender(),"PixelProtect: no world context available."); var p=SelectorParser.parse(tokens(raw),dr,dh,maxRadius,maxHours); if(!p.errors().isEmpty())return sendErrors(source.getSender(),p.errors()); CommandSender s=source.getSender(); var q=query(l,p); if(p.countOnly()){database.count(q).thenAccept(n->send(s,"PixelProtect: "+n+" matching audit record(s)."));return Command.SINGLE_SUCCESS;} database.query(q).thenCompose(e->{if(e.isEmpty()){send(s,p.preview()?"PixelProtect: preview found no matching records.":"PixelProtect: nothing to rollback.");return CompletableFuture.<String>completedFuture(null);} if(p.preview())return rollback.preview(e).thenApply(r->"preview:"+r.applied()+":"+r.skipped()); return rollback.start(e).thenApply(j->"job:"+j.id());}).thenAccept(r->{if(r==null)return;if(r.startsWith("preview:")){var x=r.split(":");send(s,"PixelProtect: preview complete. Would apply "+x[1]+", skip "+x[2]+".");}else send(s,"PixelProtect: rollback job started: "+r.substring(5)+". Use /pixelprotect rollback status <job> for progress.");}).exceptionally(t->{send(s,"PixelProtect: operation failed — "+rootMessage(t));return null;});return Command.SINGLE_SUCCESS; }
    private int restore(CommandSender sender,String raw){try{UUID id=UUID.fromString(raw);rollback.restore(id).thenAccept(r->send(sender,"PixelProtect: restore complete. Applied "+r.applied()+", skipped "+r.skipped()+".")).exceptionally(t->{send(sender,"PixelProtect: restore failed — "+rootMessage(t));return null;});return message(sender,"PixelProtect: restore started...");}catch(IllegalArgumentException e){return message(sender,"PixelProtect: invalid rollback job id.");}}
    private int rollbackStatus(CommandSender s,String raw){try{var j=rollback.status(UUID.fromString(raw));if(j==null)return message(s,"PixelProtect: rollback job not found.");return message(s,"PixelProtect: job "+j.id()+" — "+j.status()+" — processed "+j.processed()+"/"+j.total()+", applied "+j.applied()+", skipped "+j.skipped()+(j.error()==null?"":" — "+j.error()));}catch(IllegalArgumentException e){return message(s,"PixelProtect: invalid rollback job id.");}}
    private int rollbackCancel(CommandSender s,String raw){try{return message(s,rollback.cancel(UUID.fromString(raw))?"PixelProtect: rollback cancellation requested.":"PixelProtect: rollback job not found or already finished.");}catch(IllegalArgumentException e){return message(s,"PixelProtect: invalid rollback job id.");}}
    private AuditQuery query(Location l,SelectorParser.Parsed p){long n=System.currentTimeMillis();return new AuditQuery(l.getWorld().getUID(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),p.radius(),n-p.hours()*3_600_000L,n,p.user(),p.includeActions(),p.excludeActions(),p.includeBlocks(),p.excludeBlocks(),maxRecords);}
    private int purge(CommandSender s,int d){database.purgeBefore(System.currentTimeMillis()-d*86_400_000L).thenAccept(n->send(s,"PixelProtect: purged "+n+" audit records."));s.sendPlainMessage("PixelProtect: purge started...");return Command.SINGLE_SUCCESS;}
    private int status(CommandSender s){database.count().thenAccept(n->send(s,"PixelProtect: operational. Stored audit records: "+n+"."));s.sendPlainMessage("PixelProtect: checking storage...");return Command.SINGLE_SUCCESS;}
    private int help(CommandSender s){s.sendPlainMessage("PixelProtect: /pixelprotect inspect");s.sendPlainMessage("PixelProtect: /pixelprotect lookup <radius> <hours> [selectors] [#count]");s.sendPlainMessage("PixelProtect: /pixelprotect rollback <radius> <hours> [selectors] [#preview]");s.sendPlainMessage("PixelProtect: /pixelprotect rollback status <job>");s.sendPlainMessage("PixelProtect: /pixelprotect rollback cancel <job>");s.sendPlainMessage("PixelProtect: /pixelprotect restore <job>");return Command.SINGLE_SUCCESS;}
    private int version(CommandSender s){s.sendPlainMessage("PixelProtect 0.1.0 — standalone Paper 26.2 audit/rollback core");return Command.SINGLE_SUCCESS;}
    private void sendLookup(CommandSender s,List<AuditEntry> e){if(e.isEmpty()){send(s,"PixelProtect: no audit records found.");return;}send(s,"PixelProtect: "+e.size()+" audit record(s):");e.stream().limit(15).forEach(x->send(s,"#"+x.id()+" "+x.actorName()+" "+x.action()+" @ "+x.x()+","+x.y()+","+x.z()));if(e.size()>15)send(s,"PixelProtect: output limited to 15 records; use narrower selectors.");}
    private static List<String> tokens(String r){return r==null||r.isBlank()?List.of():Arrays.asList(r.trim().split("\\s+"));}
    private int sendErrors(CommandSender s,List<String> e){e.forEach(x->s.sendPlainMessage("PixelProtect: "+x));return 0;}
    private int message(CommandSender s,String m){s.sendPlainMessage(m);return Command.SINGLE_SUCCESS;}
    private void send(CommandSender s,String m){Bukkit.getGlobalRegionScheduler().run(plugin,t->s.sendPlainMessage(m));}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
}
