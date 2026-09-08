package de.pixelprotect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.service.RollbackService;
import de.pixelprotect.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

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
        final var root = Commands.literal("pixelprotect")
                .executes(ctx -> version(ctx.getSource().getSender()))
                .then(Commands.literal("version")
                        .executes(ctx -> version(ctx.getSource().getSender())))
                .then(Commands.literal("status")
                        .requires(source -> source.getSender().hasPermission("pixelprotect.status"))
                        .executes(ctx -> status(ctx.getSource().getSender())))
                .then(Commands.literal("lookup")
                        .requires(source -> source.getSender().hasPermission("pixelprotect.lookup"))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                        .executes(ctx -> lookup(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                IntegerArgumentType.getInteger(ctx, "hours"), null))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> lookup(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        IntegerArgumentType.getInteger(ctx, "hours"),
                                                        StringArgumentType.getString(ctx, "player")))))))
                .then(Commands.literal("near")
                        .requires(source -> source.getSender().hasPermission("pixelprotect.lookup"))
                        .executes(ctx -> lookup(ctx.getSource(), 5, Math.min(1, maxHours), null)))
                .then(Commands.literal("rollback")
                        .requires(source -> source.getSender().hasPermission("pixelprotect.rollback"))
                        .then(Commands.literal("preview")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius))
                                        .then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                                .executes(ctx -> rollback(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        IntegerArgumentType.getInteger(ctx, "hours"), null, true))
                                                .then(Commands.argument("player", StringArgumentType.word())
                                                        .executes(ctx -> rollback(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                                IntegerArgumentType.getInteger(ctx, "hours"),
                                                                StringArgumentType.getString(ctx, "player"), true))))))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                        .executes(ctx -> rollback(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                IntegerArgumentType.getInteger(ctx, "hours"), null, false))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> rollback(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        IntegerArgumentType.getInteger(ctx, "hours"),
                                                        StringArgumentType.getString(ctx, "player"), false))))))
                .then(Commands.literal("purge")
                        .requires(source -> source.getSender().hasPermission("pixelprotect.purge"))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                .executes(ctx -> {
                                    final int days = IntegerArgumentType.getInteger(ctx, "days");
                                    return purge(ctx.getSource().getSender(), days);
                                })));
        return root;
    }

    private int lookup(CommandSourceStack source, int radius, int hours, String player) {
        final Location location = source.getLocation();
        if (location.getWorld() == null) {
            source.getSender().sendPlainMessage("PixelProtect: no world context available.");
            return Command.SINGLE_SUCCESS;
        }
        final long now = System.currentTimeMillis();
        database.query(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                        radius, now - hours * 3_600_000L, now, player, Math.min(100, maxRecords))
                .thenAccept(entries -> sendLookup(source.getSender(), entries))
                .exceptionally(throwable -> {
                    send(source.getSender(), "PixelProtect: lookup failed — " + rootMessage(throwable));
                    return null;
                });
        source.getSender().sendPlainMessage("PixelProtect: querying audit history...");
        return Command.SINGLE_SUCCESS;
    }

    private int rollback(CommandSourceStack source, int radius, int hours, String player, boolean preview) {
        final Location location = source.getLocation();
        if (location.getWorld() == null) {
            source.getSender().sendPlainMessage("PixelProtect: no world context available.");
            return Command.SINGLE_SUCCESS;
        }
        final long now = System.currentTimeMillis();
        final CommandSender sender = source.getSender();
        database.query(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                        radius, now - hours * 3_600_000L, now, player, maxRecords)
                .thenCompose(entries -> {
                    if (entries.isEmpty()) {
                        send(sender, preview ? "PixelProtect: preview found no records." : "PixelProtect: nothing to rollback.");
                        return CompletableFuture.completedFuture(new RollbackService.Result(0, 0));
                    }
                    if (preview) {
                        send(sender, "PixelProtect: rollback preview — " + entries.size() + " record(s) would be evaluated.");
                        return CompletableFuture.completedFuture(new RollbackService.Result(0, entries.size()));
                    }
                    send(sender, "PixelProtect: rolling back " + entries.size() + " audit records...");
                    return rollback.rollback(entries);
                })
                .thenAccept(result -> {
                    if (!preview) {
                        send(sender, "PixelProtect: rollback complete. Applied "
                                + result.applied() + ", skipped " + result.skipped() + ".");
                    }
                })
                .exceptionally(throwable -> {
                    send(sender, "PixelProtect: operation failed — " + rootMessage(throwable));
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    private int purge(CommandSender sender, int days) {
        final long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        database.purgeBefore(cutoff)
                .thenAccept(count -> send(sender, "PixelProtect: purged " + count + " audit records."))
                .exceptionally(throwable -> {
                    send(sender, "PixelProtect: purge failed — " + rootMessage(throwable));
                    return null;
                });
        sender.sendPlainMessage("PixelProtect: purge started...");
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandSender sender) {
        database.count()
                .thenAccept(count -> send(sender, "PixelProtect: operational. Stored audit records: " + count + "."))
                .exceptionally(throwable -> {
                    send(sender, "PixelProtect: status unavailable — " + rootMessage(throwable));
                    return null;
                });
        sender.sendPlainMessage("PixelProtect: checking storage...");
        return Command.SINGLE_SUCCESS;
    }

    private int version(CommandSender sender) {
        sender.sendPlainMessage("PixelProtect 0.1.0 — standalone Paper 26.2 audit/rollback core");
        return Command.SINGLE_SUCCESS;
    }

    private void sendLookup(CommandSender sender, List<AuditEntry> entries) {
        if (entries.isEmpty()) {
            send(sender, "PixelProtect: no audit records found.");
            return;
        }
        send(sender, "PixelProtect: " + entries.size() + " audit record(s):");
        for (AuditEntry entry : entries.stream().limit(15).toList()) {
            send(sender, "#" + entry.id() + " " + entry.actorName() + " " + entry.action()
                    + " @ " + entry.x() + "," + entry.y() + "," + entry.z());
        }
    }

    private void send(CommandSender sender, String message) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> sender.sendPlainMessage(message));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
