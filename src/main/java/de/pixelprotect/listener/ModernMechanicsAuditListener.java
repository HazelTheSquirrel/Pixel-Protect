package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import io.papermc.paper.event.block.BlockFailedDispenseEvent;
import io.papermc.paper.event.block.BlockPreDispenseEvent;
import io.papermc.paper.event.block.CompostItemEvent;
import io.papermc.paper.event.block.DragonEggFormEvent;
import io.papermc.paper.event.block.VaultChangeStateEvent;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockShearEntityEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SculkBloomEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.plugin.Plugin;

/** Modern Paper mechanics coverage. Async attribution is always returned to the owning region before Bukkit state is touched. */
public final class ModernMechanicsAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;

    public ModernMechanicsAuditListener(Plugin plugin, AuditService audit) { this.plugin = plugin; this.audit = audit; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreakBlock(BlockBreakBlockEvent event) {
        Block target = event.getBlock();
        Block source = event.getSource();
        BlockSnapshot before = BlockSnapshot.capture(target);
        audit.latestPlacementActor(source).thenAccept(owner -> Bukkit.getRegionScheduler().run(plugin, target.getLocation(), task -> audit.record(target, ActionType.BLOCK_BREAK, owner == null ? Actor.environment() : owner, before, air(), audit.newTransaction(), 0L)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        for (BlockState state : event.getBlocks()) audit.recordEnvironment(state.getBlock(), ActionType.FLUID, BlockSnapshot.fromState(state), air());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidLevelChange(FluidLevelChangeEvent event) {
        Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.FLUID, BlockSnapshot.capture(block), new BlockSnapshot(event.getNewData().getAsString(), null, null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoistureChange(MoistureChangeEvent event) {
        audit.recordEnvironment(event.getBlock(), ActionType.MOISTURE, BlockSnapshot.capture(event.getBlock()), BlockSnapshot.fromState(event.getNewState()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        audit.recordEnvironment(event.getBlock(), ActionType.DECAY, BlockSnapshot.capture(event.getBlock()), air());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSculkBloom(SculkBloomEvent event) {
        Block block = event.getBlock();
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.recordEnvironment(block, ActionType.SCULK, snapshot, snapshot);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent event) {
        audit.record(event.getBlock(), ActionType.CAULDRON, actor(event.getEntity()), BlockSnapshot.capture(event.getBlock()), BlockSnapshot.fromState(event.getNewState()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        Block block = event.getBlock();
        audit.record(block, ActionType.TNT_PRIME, actor(event.getPrimingEntity()), BlockSnapshot.capture(block), BlockSnapshot.capture(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockPreDispenseEvent event) {
        Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.DISPENSE, BlockSnapshot.capture(block), BlockSnapshot.capture(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFailedDispense(BlockFailedDispenseEvent event) {
        Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.DISPENSE, BlockSnapshot.capture(block), BlockSnapshot.capture(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCompost(CompostItemEvent event) {
        Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.COMPOST, BlockSnapshot.capture(block), BlockSnapshot.capture(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(BlockShearEntityEvent event) {
        Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.SHEAR, BlockSnapshot.capture(block), BlockSnapshot.capture(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonEgg(DragonEggFormEvent event) {
        audit.recordEnvironment(event.getBlock(), ActionType.FORM, BlockSnapshot.capture(event.getBlock()), BlockSnapshot.fromState(event.getNewState()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVault(VaultChangeStateEvent event) {
        Player player = event.getPlayer();
        Actor actor = player == null ? Actor.environment() : new Actor(player.getUniqueId(), player.getName());
        audit.record(event.getBlock(), ActionType.VAULT, actor, BlockSnapshot.capture(event.getBlock()), BlockSnapshot.capture(event.getBlock()));
    }

    private static BlockSnapshot air() { return new BlockSnapshot(org.bukkit.Material.AIR.createBlockData().getAsString(), null, null); }
    private static Actor actor(Entity entity) { return entity instanceof Player player ? new Actor(player.getUniqueId(), player.getName()) : Actor.environment(); }
}
