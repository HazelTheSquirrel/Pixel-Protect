package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;

/** Entity forensic events with explicit cause/attacker metadata. */
public final class EntityForensicsAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;
    private final boolean damageEnabled;

    public EntityForensicsAuditListener(Plugin plugin, AuditService audit, boolean damageEnabled) {
        this.plugin = plugin;
        this.audit = audit;
        this.damageEnabled = damageEnabled;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!damageEnabled) return;
        Entity victim = event.getEntity();
        Entity damager = event instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;
        if (!(victim instanceof Player) && !(damager instanceof Player)) return;

        Block block = victim.getLocation().getBlock();
        String detail = "CAUSE:" + event.getCause().name() + ":DAMAGE=" + event.getFinalDamage();
        if (damager != null) detail += ":DAMAGER=" + damager.getUniqueId() + ":TYPE=" + damager.getType().getKey();
        if (damager instanceof Player player) {
            audit.recordPlayer(block, ActionType.ENTITY_DAMAGE, player, snapshot(block), snapshot(block), detail, audit.newTransaction(), 0L);
        } else {
            audit.record(block, ActionType.ENTITY_DAMAGE, Actor.environment(), snapshot(block), snapshot(block), detail, audit.newTransaction(), 0L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Block block = victim.getLocation().getBlock();
        String killer = victim.getKiller() == null ? "none" : victim.getKiller().getUniqueId().toString();
        String detail = "CAUSE=DEATH:KILLER=" + killer + ":VICTIM=" + victim.getUniqueId()
                + ":TYPE=" + victim.getType().getKey() + ":DROPS=" + event.getDrops().size()
                + ":XP=" + event.getDroppedExp();
        audit.record(block, ActionType.ENTITY_DEATH, Actor.environment(), snapshot(block), snapshot(block), detail, audit.newTransaction(), 0L);
    }

    private static BlockSnapshot snapshot(Block block) { return BlockSnapshot.capture(block); }
}
