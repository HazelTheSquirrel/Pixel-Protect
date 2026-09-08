package de.pixelprotect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.AuditQuery;
import de.pixelprotect.service.RollbackService;
import de.pixelprotect.storage.Database;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class PixelProtectCommand {
    private final Plugin plugin;
    private final Database database;
    private final RollbackService rollback;
    private final int maxHours;
    private final int maxRadius;
    private final int maxRecords;

    public PixelProtectCommand(Plugin plugin, Database database, RollbackService rollback,
                               int maxHours, int maxRadius, int maxRecords) {
        this.plugin = plugin;
        this.database = database;
        this.rollback = rollback;
        this.maxHours = maxHours;
        this.maxRadius = maxRadius;
        this.maxRecords = maxRecords;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("pixelprotect")
                .executes(ctx -> version(ctx.getSource().getSender()))
                .then(Commands.literal("version").executes(ctx -> version(ctx.getSource().getSender())))
                .then(Commands.literal("status")
                        .requires(s -> s.getSender().hasPermission("pixelprotect.status"))
                        .executes(ctx -> status(ctx.getSource().getSender())))
                .then(Commands.literal("lookup")
                        .requires(s -> s.getSender().hasPermission("pixelprotect.lookup"))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                        .executes(ctx -> lookup(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"),
                                                IntegerArgumentType.getInteger(ctx, "hours"), ""))
                                        .then(Commands.argument("selectors", StringArgumentType.greedyString())
                                                .executes(ctx -> lookup(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"),
                                                        IntegerArgumentType.getInteger(ctx, "hours"), StringArgumentType.getString(ctx, "selectors")))))))
                .then(Commands.literal("near")
                        .requires(s -> s.getSender().hasPermission("pixelprotect.lookup"))
                        .executes(ctx -> lookup(ctx.getSource(), 5, 1, ""))
                        .then(Commands.argument("selectors", StringArgumentType.greedyString())
                                .executes(ctx -> lookup(ctx.getSource(), 5, 1, StringArgumentType.getString(ctx, "selectors")))))
                .then(Commands.literal("rollback")
                        .requires(s -> s.getSender().hasPermission("pixelprotect.rollback"))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                        .executes(ctx -> rollback(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"),
                                                IntegerArgumentType.getInteger(ctx, "hours"), ""))
                                        .then(Commands.argument("selectors", StringArgumentType.greedyString())
                                                .executes(ctx -> rollback(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"),
                                                        IntegerArgumentType.getInteger(ctx, "hours"), StringArgumentType.getString(ctx, "selectors")))))))
                .then(Commands.literal("purge")
                        .requires(s -> s.getSender().hasPermission("pixelprotect.purge"))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                .executes(ctx -> purge(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "days")))))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource().getSender())));
    }

    private int lookup(CommandSourceStack source, int defaultRadius, int defaultHours, String rawSelectors) {
        final Location location = source.getLocation();
        if (location.getWorld() == null) return message(source.getSender(), "PixelProtect: no world context available.");
        final SelectorParser.Parsed parsed = SelectorParser.parse(tokens(rawSelectors), defaultRadius, defaultHours, maxRadius, maxHours);
        if (!parsed.errors().isEmpty()) return sendErrors(source.getSender(), parsed.errors());
        final long now = System.currentTimeMillis();
        final AuditQuery query = query(location, parsed, now);
        database.query(query).thenAccept(entries -> {
            if (parsed.countOnly()) send(source.getSender(), "PixelProtect: " + entries.size() + " matching audit record(s).");
            else sendLookup(source.getSender(), entries);
        }).exceptionally(t -> { send(source.getSender(), "PixelProtect: lookup failed — " + rootMessage(t)); return null; });
        source.getSender().sendPlainMessage("PixelProtect: querying audit history...");
        return Command.SINGLE_SUCCESS;
    }

    private int rollback(CommandSourceStack source, int defaultRadius, int defaultHours, String rawSelectors) {
        final Location location = source.getLocation();
        if (location.getWorld() == null) return message(source.getSender(), "PixelProtect: no world context available.");
        final SelectorParser.Parsed parsed = SelectorParser.parse(tokens(rawSelectors), defaultRadius, defaultHours, maxRadius, maxHours);
        if (!parsed.errors().isEmpty()) return sendErrors(source.getSender(), parsed.errors());
        final long now = System.currentTimeMillis();
        final AuditQuery query = query(location, parsed, now);
        final CommandSender sender = source.getSender();
        database.query(query).thenCompose(entries -> {
            if (entries.isEmpty()) {
                send(sender, parsed.preview() ? "PixelProtect: preview found no matching records." : "PixelProtect: nothing to rollback.");
                return CompletableFuture.completedFuture(new RollbackService.Result(0, 0));
            }
            if (parsed.countOnly()) {
                send(sender, "PixelProtect: " + entries.size() + " matching audit record(s).");
                return CompletableFuture.completedFuture(new RollbackService.Result(0, 0));
            }
            if (parsed.preview()) {
                send(sender, "PixelProtect: evaluating rollback guards for " + entries.size() + " record(s)...");
                return rollback.preview(entries);
            }
            send(sender, "PixelProtect: rolling back " + entries.size() + " audit record(s)...");
            return rollback.rollback(entries);
        }).thenAccept(result -> {
            if (parsed.preview()) send(sender, "PixelProtect: preview complete. Would apply " + result.applied() + ", skip " + result.skipped() + ".");
            else if (!parsed.countOnly()) send(sender, "PixelProtect: rollback complete. Applied " + result.applied() + ", skipped " + result.skipped() + ".");
        }).exceptionally(t -> { send(sender, "PixelProtect: operation failed — " + rootMessage(t)); return null; });
        return Command.SINGLE_SUCCESS;
    }

    private AuditQuery query(Location location, SelectorParser.Parsed parsed, long now) {
        return new AuditQuery(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                parsed.radius(), now - parsed.hours() * 3_600_000L, now, parsed.user(), parsed.includeActions(), parsed.excludeActions(),
                parsed.includeBlocks(), parsed.excludeBlocks(), parsed.countOnly() ? maxRecords : maxRecords);
    }

    private int purge(CommandSender sender, int days) {
        database.purgeBefore(System.currentTimeMillis() - days * 86_400_000L)
                .thenAccept(count -> send(sender, "PixelProtect: purged " + count + " audit records."))
                .exceptionally(t -> { send(sender, "PixelProtect: purge failed — " + rootMessage(t)); return null; });
        sender.sendPlainMessage("PixelProtect: purge started...");
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandSender sender) {
        database.count().thenAccept(count -> send(sender, "PixelProtect: operational. Stored audit records: " + count + "."))
                .exceptionally(t -> { send(sender, "PixelProtect: status unavailable — " + rootMessage(t)); return null; });
        sender.sendPlainMessage("PixelProtect: checking storage...");
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandSender sender) {
        sender.sendPlainMessage("PixelProtect: /pixelprotect lookup <radius> <hours> [u:<player>] [r:<radius>] [t:<time>] [a:<actions>] [i:<blocks>] [e:<blocks>] [#count]");
        sender.sendPlainMessage("PixelProtect: /pixelprotect rollback <radius> <hours> [selectors] [#preview]");
        sender.sendPlainMessage("PixelProtect: time accepts m/h/d; actions are ActionType names such as BREAK,PLACE,EXPLOSION.");
        return Command.SINGLE_SUCCESS;
    }

    private int version(CommandSender sender) { sender.sendPlainMessage("PixelProtect 0.1.0 — standalone Paper 26.2 audit/rollback core"); return Command.SINGLE_SUCCESS; }

    private void sendLookup(CommandSender sender, List<AuditEntry> entries) {
        if (entries.isEmpty()) { send(sender, "PixelProtect: no audit records found."); return; }
        send(sender, "PixelProtect: " + entries.size() + " audit record(s):");
        for (AuditEntry entry : entries.stream().limit(15).toList())
            send(sender, "#" + entry.id() + " " + entry.actorName() + " " + entry.action() + " @ " + entry.x() + "," + entry.y() + "," + entry.z());
        if (entries.size() > 15) send(sender, "PixelProtect: output limited to 15 records; use narrower selectors.");
    }

    private static List<String> tokens(String raw) { return raw == null || raw.isBlank() ? List.of() : Arrays.asList(raw.trim().split("\\s+")); }
    private int sendErrors(CommandSender sender, List<String> errors) { errors.forEach(error -> sender.sendPlainMessage("PixelProtect: " + error)); return 0; }
    private int message(CommandSender sender, String message) { sender.sendPlainMessage(message); return Command.SINGLE_SUCCESS; }
    private void send(CommandSender sender, String message) { Bukkit.getGlobalRegionScheduler().run(plugin, task -> sender.sendPlainMessage(message)); }
    private static String rootMessage(Throwable throwable) { Throwable current = throwable; while (current.getCause() != null) current = current.getCause(); return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage(); }
}
