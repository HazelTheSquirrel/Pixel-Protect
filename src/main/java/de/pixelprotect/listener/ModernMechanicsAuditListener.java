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

import java.util.UUID;

/** Modern Paper mechanics coverage. State-changing events store state; pure actions store explicit details. */
public final class ModernMechanicsAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;

    public ModernMechanicsAuditListener(Plugin plugin, AuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreakBlock(BlockBreakBlockEvent event) {
        Block target = event.getBlock();
        Block source = event.getSource();
        BlockSnapshot before = BlockSnapshot.capture(target);
        audit.latestPlacementActor(source).thenAccept(owner -> Bukkit.getRegionScheduler().run(plugin, target.getLocation(), task ->
                audit.record(target, ActionType.BLOCK_BREAK, owner == null ? Actor.environment() : owner, before, air(),
                        "SOURCE:" + source.getType().getKey(), audit.newTransaction(), 0L)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        UUID transaction = audit.newTransaction();
        long sequence = 0;
        for (BlockState state : event.getBlocks()) {
            audit.recordEnvironment(state.getBlock(), ActionType.FLUID, BlockSnapshot.fromState(state), air(),
                    "MECHANISM:SPONGE", transaction, sequence++);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidLevelChange(FluidLevelChangeEvent event) {
        Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.FLUID, BlockSnapshot.capture(block),
                new BlockSnapshot(event.getNewData().getAsString(), null, null), "NEW_FLUID:" + event.getNewData().getAsString(), audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoistureChange(MoistureChangeEvent event) {
        audit.recordEnvironment(event.getBlock(), ActionType.MOISTURE, BlockSnapshot.capture(event.getBlock()),
                BlockSnapshot.fromState(event.getNewState()), "MOISTURE_CHANGE", audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        audit.recordEnvironment(event.getBlock(), ActionType.DECAY, BlockSnapshot.capture(event.getBlock()), air(),
                "NATURAL_DECAY", audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSculkBloom(SculkBloomEvent event) {
        Block block = event.getBlock();
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.recordEnvironment(block, ActionType.SCULK, snapshot, snapshot,
                "SCULK_BLOOM", audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent event) {
        audit.record(event.getBlock(), ActionType.CAULDRON, actor(event.getEntity()), BlockSnapshot.capture(event.getBlock()),
                BlockSnapshot.fromState(event.getNewState()), "CAUSE:" + event.getReason().name(), audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        Block block = event.getBlock();
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.record(block, ActionType.TNT_PRIME, actor(event.getPrimingEntity()), snapshot, snapshot,
                "CAUSE:" + event.getCause().name() + ":BLOCK:" + (event.getPrimingBlock() == null ? "none" : event.getPrimingBlock().getType().getKey()),
                audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockPreDispenseEvent event) {
        Block block = event.getBlock();
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.recordEnvironment(block, ActionType.DISPENSE, snapshot, snapshot,
                "DISPENSE_PREPARE", audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFailedDispense(BlockFailedDispenseEvent event) {
        Block block = event.getBlock();
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.recordEnvironment(block, ActionType.DISPENSE, snapshot, snapshot,
                "FAILED_DISPENSE", audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCompost(CompostItemEvent event) {
        Block block = event.getBlock();
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.recordEnvironment(block, ActionType.COMPOST, snapshot, snapshot,
                "ITEM:" + event.getItem().getType().getKey() + ":" + event.getItem().getAmount() + ":RAISES:" + event.willRaiseLevel(), audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(BlockShearEntityEvent event) {
        Block block = event.getBlock();
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.recordEnvironment(block, ActionType.SHEAR, snapshot, snapshot,
                "ENTITY:" + event.getEntity().getUniqueId() + ":DROPS:" + event.getDrops().size(), audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonEgg(DragonEggFormEvent event) {
        audit.recordEnvironment(event.getBlock(), ActionType.FORM, BlockSnapshot.capture(event.getBlock()),
                BlockSnapshot.fromState(event.getNewState()), "DRAGON_EGG_FORM", audit.newTransaction(), 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVault(VaultChangeStateEvent event) {
        Player player = event.getPlayer();
        Actor actor = player == null ? Actor.environment() : new Actor(player.getUniqueId(), player.getName());
        Block block = event.getBlock();
        BlockSnapshot snapshot = BlockSnapshot.capture(block);
        audit.record(block, ActionType.VAULT, actor, snapshot, snapshot,
                "STATE:" + event.getCurrentState().name() + "->" + event.getNewState().name(), audit.newTransaction(), 0L);
    }

    private static BlockSnapshot air() { return new BlockSnapshot("minecraft:air", null, null); }

    private static Actor actor(Entity entity) {
        return entity instanceof Player player ? new Actor(player.getUniqueId(), player.getName()) : Actor.environment();
    }
}
