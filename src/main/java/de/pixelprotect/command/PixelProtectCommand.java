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
    private final Plugin plugin;
    private final Database database;
    private final RollbackService rollback;
    private final InspectService inspect;
    private final int maxHours;
    private final int maxRadius;
    private final int maxRecords;

    public PixelProtectCommand(Plugin plugin, Database database, RollbackService rollback, InspectService inspect, int maxHours, int maxRadius, int maxRecords) {
        this.plugin = plugin;
        this.database = database;
        this.rollback = rollback;
        this.inspect = inspect;
        this.maxHours = maxHours;
        this.maxRadius = maxRadius;
        this.maxRecords = maxRecords;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("pixelprotect")
                .executes(c -> version(c.getSource().getSender()))
                .then(Commands.literal("version").executes(c -> version(c.getSource().getSender())))
                .then(Commands.literal("status").requires(s -> s.getSender().hasPermission("pixelprotect.status")).executes(c -> status(c.getSource().getSender())))
                .then(Commands.literal("inspect").requires(s -> s.getSender().hasPermission("pixelprotect.inspect")).executes(c -> inspect(c.getSource().getSender())))
                .then(Commands.literal("lookup").requires(s -> s.getSender().hasPermission("pixelprotect.lookup"))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius)).then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                .executes(c -> lookup(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), ""))
                                .then(Commands.argument("selectors", StringArgumentType.greedyString()).executes(c -> lookup(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), StringArgumentType.getString(c, "selectors")))))))
                .then(Commands.literal("near").requires(s -> s.getSender().hasPermission("pixelprotect.lookup"))
                        .executes(c -> lookup(c.getSource(), 5, 1, ""))
                        .then(Commands.argument("selectors", StringArgumentType.greedyString()).executes(c -> lookup(c.getSource(), 5, 1, StringArgumentType.getString(c, "selectors")))))
                .then(Commands.literal("rollback").requires(s -> s.getSender().hasPermission("pixelprotect.rollback"))
                        .then(Commands.literal("status").then(Commands.argument("job", StringArgumentType.word()).executes(c -> rollbackStatus(c.getSource().getSender(), StringArgumentType.getString(c, "job")))))
                        .then(Commands.literal("cancel").then(Commands.argument("job", StringArgumentType.word()).executes(c -> rollbackCancel(c.getSource().getSender(), StringArgumentType.getString(c, "job")))))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius)).then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                .executes(c -> rollback(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), ""))
                                .then(Commands.argument("selectors", StringArgumentType.greedyString()).executes(c -> rollback(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), StringArgumentType.getString(c, "selectors")))))))
                .then(Commands.literal("restore").requires(s -> s.getSender().hasPermission("pixelprotect.rollback"))
                        .then(Commands.argument("job", StringArgumentType.word()).executes(c -> restore(c.getSource().getSender(), StringArgumentType.getString(c, "job")))))
                .then(Commands.literal("purge").requires(s -> s.getSender().hasPermission("pixelprotect.purge"))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650)).executes(c -> purge(c.getSource().getSender(), IntegerArgumentType.getInteger(c, "days")))))
                .then(Commands.literal("help").executes(c -> help(c.getSource().getSender())));
    }

    private int inspect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendPlainMessage("PixelProtect: inspect is only available to players.");
            return 0;
        }
        final boolean enabled = inspect.toggle(player);
        player.sendPlainMessage(enabled ? "PixelProtect: inspect enabled. Click a block to inspect its history." : "PixelProtect: inspect disabled.");
        return Command.SINGLE_SUCCESS;
    }

    private int lookup(CommandSourceStack source, int defaultRadius, int defaultHours, String raw) {
        final Location location = source.getLocation();
        if (location.getWorld() == null) return message(source.getSender(), "PixelProtect: no world context available.");
        final var parsed = SelectorParser.parse(tokens(raw), defaultRadius, defaultHours, maxRadius, maxHours);
        if (!parsed.errors().isEmpty()) return sendErrors(source.getSender(), parsed.errors());
        final var query = query(location, parsed);
        if (parsed.countOnly()) database.count(query).thenAccept(count -> send(source.getSender(), "PixelProtect: " + count + " matching audit record(s)."));
        else database.query(query).thenAccept(entries -> sendLookup(source.getSender(), entries));
        source.getSender().sendPlainMessage("PixelProtect: querying audit history...");
        return Command.SINGLE_SUCCESS;
    }

    private int rollback(CommandSourceStack source, int defaultRadius, int defaultHours, String raw) {
        final Location location = source.getLocation();
        if (location.getWorld() == null) return message(source.getSender(), "PixelProtect: no world context available.");
        final var parsed = SelectorParser.parse(tokens(raw), defaultRadius, defaultHours, maxRadius, maxHours);
        if (!parsed.errors().isEmpty()) return sendErrors(source.getSender(), parsed.errors());
        final CommandSender sender = source.getSender();
        final var query = query(location, parsed);
        if (parsed.countOnly()) {
            database.count(query).thenAccept(count -> send(sender, "PixelProtect: " + count + " matching audit record(s)."));
            return Command.SINGLE_SUCCESS;
        }
        database.query(query).thenCompose(entries -> {
            if (entries.isEmpty()) {
                send(sender, parsed.preview() ? "PixelProtect: preview found no matching records." : "PixelProtect: nothing to rollback.");
                return CompletableFuture.<String>completedFuture(null);
            }
            if (parsed.preview()) return rollback.preview(entries).thenApply(result -> "preview:" + result.applied() + ":" + result.skipped());
            return rollback.start(entries).thenApply(job -> "job:" + job.id());
        }).thenAccept(result -> {
            if (result == null) return;
            if (result.startsWith("preview:")) {
                final var values = result.split(":");
                send(sender, "PixelProtect: preview complete. Would apply " + values[1] + ", skip " + values[2] + ".");
            } else {
                send(sender, "PixelProtect: rollback job started: " + result.substring(4) + ". Use /pixelprotect rollback status <job> for progress.");
            }
        }).exceptionally(throwable -> {
            send(sender, "PixelProtect: operation failed — " + rootMessage(throwable));
            return null;
        });
        return Command.SINGLE_SUCCESS;
    }

    private int restore(CommandSender sender, String raw) {
        try {
            final UUID id = UUID.fromString(raw);
            rollback.restore(id)
                    .thenAccept(result -> send(sender, "PixelProtect: restore complete. Applied " + result.applied() + ", skipped " + result.skipped() + "."))
                    .exceptionally(throwable -> {
                        send(sender, "PixelProtect: restore failed — " + rootMessage(throwable));
                        return null;
                    });
            return message(sender, "PixelProtect: restore started...");
        } catch (IllegalArgumentException exception) {
            return message(sender, "PixelProtect: invalid rollback job id.");
        }
    }

    private int rollbackStatus(CommandSender sender, String raw) {
        try {
            final UUID id = UUID.fromString(raw);
            rollback.statusAsync(id).thenAccept(job -> {
                if (job == null) send(sender, "PixelProtect: rollback job not found.");
                else send(sender, "PixelProtect: job " + job.id() + " — " + job.status() + " — processed " + job.processed() + "/" + job.total() + ", applied " + job.applied() + ", skipped " + job.skipped() + (job.error() == null ? "" : " — " + job.error()));
            }).exceptionally(throwable -> {
                send(sender, "PixelProtect: status failed — " + rootMessage(throwable));
                return null;
            });
            return message(sender, "PixelProtect: loading rollback job status...");
        } catch (IllegalArgumentException exception) {
            return message(sender, "PixelProtect: invalid rollback job id.");
        }
    }

    private int rollbackCancel(CommandSender sender, String raw) {
        try {
            final UUID id = UUID.fromString(raw);
            rollback.cancelAsync(id).thenAccept(cancelled -> send(sender, cancelled ? "PixelProtect: rollback cancellation requested." : "PixelProtect: rollback job not found or already finished."));
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException exception) {
            return message(sender, "PixelProtect: invalid rollback job id.");
        }
    }

    private AuditQuery query(Location location, SelectorParser.Parsed parsed) {
        final long now = System.currentTimeMillis();
        return new AuditQuery(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), parsed.radius(),
                now - parsed.hours() * 3_600_000L, now, parsed.user(), parsed.includeActions(), parsed.excludeActions(),
                parsed.includeBlocks(), parsed.excludeBlocks(), maxRecords);
    }

    private int purge(CommandSender sender, int days) {
        database.purgeBefore(System.currentTimeMillis() - days * 86_400_000L).thenAccept(count -> send(sender, "PixelProtect: purged " + count + " audit records."));
        sender.sendPlainMessage("PixelProtect: purge started...");
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandSender sender) {
        database.count().thenAccept(count -> send(sender, "PixelProtect: operational. Stored audit records: " + count + "."));
        sender.sendPlainMessage("PixelProtect: checking storage...");
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandSender sender) {
        sender.sendPlainMessage("PixelProtect: /pixelprotect inspect");
        sender.sendPlainMessage("PixelProtect: /pixelprotect lookup <radius> <hours> [selectors] [#count]");
        sender.sendPlainMessage("PixelProtect: /pixelprotect rollback <radius> <hours> [selectors] [#preview]");
        sender.sendPlainMessage("PixelProtect: /pixelprotect rollback status <job>");
        sender.sendPlainMessage("PixelProtect: /pixelprotect rollback cancel <job>");
        sender.sendPlainMessage("PixelProtect: /pixelprotect restore <job>");
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
        entries.stream().limit(15).forEach(entry -> send(sender, "#" + entry.id() + " " + entry.actorName() + " " + entry.action() + " @ " + entry.x() + "," + entry.y() + "," + entry.z()));
        if (entries.size() > 15) send(sender, "PixelProtect: output limited to 15 records; use narrower selectors.");
    }

    private static List<String> tokens(String raw) {
        return raw == null || raw.isBlank() ? List.of() : Arrays.asList(raw.trim().split("\\s+"));
    }

    private int sendErrors(CommandSender sender, List<String> errors) {
        errors.forEach(error -> sender.sendPlainMessage("PixelProtect: " + error));
        return 0;
    }

    private int message(CommandSender sender, String message) {
        sender.sendPlainMessage(message);
        return Command.SINGLE_SUCCESS;
    }

    private void send(CommandSender sender, String message) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> sender.sendPlainMessage(message));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
