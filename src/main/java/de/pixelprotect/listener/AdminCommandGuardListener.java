package de.pixelprotect.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/** Enforces the server-owner policy that PixelProtect commands are available only to OP players. */
public final class AdminCommandGuardListener implements Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        final Player player = event.getPlayer();
        final String command = event.getMessage().trim();
        if (!isPixelProtectCommand(command) || player.isOp()) return;
        event.setCancelled(true);
        player.sendMessage(Component.text("PixelProtect: Dieser Befehl ist ausschließlich für OP-Spieler verfügbar.", NamedTextColor.RED));
    }

    private static boolean isPixelProtectCommand(String command) {
        if (command.isEmpty() || command.charAt(0) != '/') return false;
        final String withoutSlash = command.substring(1);
        final int separator = withoutSlash.indexOf(' ');
        final String label = separator < 0 ? withoutSlash : withoutSlash.substring(0, separator);
        return label.equalsIgnoreCase("pixelprotect");
    }
}
