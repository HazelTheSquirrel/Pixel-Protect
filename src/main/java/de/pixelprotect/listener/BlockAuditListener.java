package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.entity.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;

public final class BlockAuditListener implements Listener {
    private final AuditService audit;

    public BlockAuditListener(AuditService audit) {
        this.audit = audit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        final Block block = event.getBlock();
        audit.recordPlayer(block, ActionType.BREAK, event.getPlayer(), BlockSnapshot.capture(block), air(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent) {
            return;
        }
        final Block block = event.getBlockPlaced();
        audit.recordPlayer(block, ActionType.PLACE, event.getPlayer(),
                BlockSnapshot.fromState(event.getBlockReplacedState()), BlockSnapshot.capture(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        for (var replaced : event.getReplacedBlockStates()) {
            final Block block = replaced.getBlock();
            audit.recordPlayer(block, ActionType.PLACE, event.getPlayer(),
                    BlockSnapshot.fromState(replaced), BlockSnapshot.capture(block));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        final Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.BURN, BlockSnapshot.capture(block), air(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            audit.recordEnvironment(block, ActionType.EXPLOSION, BlockSnapshot.capture(block), air(block));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            audit.recordEntity(block, ActionType.EXPLOSION, event.getEntity(), BlockSnapshot.capture(block), air(block));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        final Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.FORM, BlockSnapshot.capture(block), BlockSnapshot.fromState(event.getNewState()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        final Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.FORM, BlockSnapshot.capture(block), BlockSnapshot.fromState(event.getNewState()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        final Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.GROW, BlockSnapshot.capture(block), BlockSnapshot.fromState(event.getNewState()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        final Block block = event.getBlock();
        audit.recordEnvironment(block, ActionType.SPREAD, BlockSnapshot.capture(block), BlockSnapshot.fromState(event.getNewState()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        final Actor actor = event.getPlayer() == null ? Actor.environment()
                : new Actor(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        for (var state : event.getBlocks()) {
            final Block block = state.getBlock();
            audit.record(block, ActionType.GROW, actor, BlockSnapshot.capture(block), BlockSnapshot.fromState(state));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        final Actor actor = event.getPlayer() == null ? Actor.environment()
                : new Actor(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        for (var state : event.getBlocks()) {
            final Block block = state.getBlock();
            audit.record(block, ActionType.GROW, actor, BlockSnapshot.capture(block), BlockSnapshot.fromState(state));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        final Block destination = event.getToBlock();
        audit.recordEnvironment(destination, ActionType.FLUID, BlockSnapshot.capture(destination),
                BlockSnapshot.capture(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        final Block block = event.getBlock();
        audit.recordEntity(block, ActionType.ENTITY_CHANGE, event.getEntity(), BlockSnapshot.capture(block),
                new BlockSnapshot(event.getBlockData().getAsString(), null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityForm(EntityBlockFormEvent event) {
        final Block block = event.getBlock();
        audit.recordEntity(block, ActionType.FORM, event.getEntity(), BlockSnapshot.capture(block),
                BlockSnapshot.fromState(event.getBlockState()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        recordPiston(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        recordPiston(event.getBlocks(), event.getDirection().getOppositeFace());
    }

    private void recordPiston(List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            final BlockSnapshot before = BlockSnapshot.capture(block);
            final Block destination = block.getRelative(direction);
            audit.recordEnvironment(block, ActionType.PISTON, before, air(block));
            audit.recordEnvironment(destination, ActionType.PISTON, BlockSnapshot.capture(destination), before);
        }
    }

    private static BlockSnapshot air(Block block) {
        return new BlockSnapshot(Bukkit.createBlockData(org.bukkit.Material.AIR).getAsString(), null);
    }
}
