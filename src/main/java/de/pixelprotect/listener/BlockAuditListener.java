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
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

public final class BlockAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;
    private final boolean placeBreak;
    private final boolean explosions;
    private final boolean fire;
    private final boolean piston;
    private final boolean fluids;
    private final boolean growth;
    private final boolean entityChanges;

    public BlockAuditListener(Plugin plugin, AuditService audit, boolean placeBreak, boolean explosions, boolean fire,
                               boolean piston, boolean fluids, boolean growth, boolean entityChanges) {
        this.plugin = plugin;
        this.audit = audit;
        this.placeBreak = placeBreak;
        this.explosions = explosions;
        this.fire = fire;
        this.piston = piston;
        this.fluids = fluids;
        this.growth = growth;
        this.entityChanges = entityChanges;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!placeBreak) return;
        final Block block = event.getBlock();
        final BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.recordPlayer(block, ActionType.BREAK, event.getPlayer(), before, BlockSnapshot.capture(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!placeBreak || event instanceof BlockMultiPlaceEvent) return;
        final Block block = event.getBlockPlaced();
        final BlockSnapshot before = BlockSnapshot.fromState(event.getBlockReplacedState());
        later(block, () -> audit.recordPlayer(block, ActionType.PLACE, event.getPlayer(), before, BlockSnapshot.capture(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        if (!placeBreak) return;
        final List<BlockSnapshot> before = new ArrayList<>();
        final List<Block> blocks = new ArrayList<>();
        for (var state : event.getReplacedBlockStates()) {
            blocks.add(state.getBlock());
            before.add(BlockSnapshot.fromState(state));
        }
        if (blocks.isEmpty()) return;
        later(blocks.getFirst(), () -> {
            for (int i = 0; i < blocks.size(); i++) {
                final Block block = blocks.get(i);
                audit.recordPlayer(block, ActionType.PLACE, event.getPlayer(), before.get(i), BlockSnapshot.capture(block));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (!fire) return;
        final Block block = event.getBlock();
        final BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.recordEnvironment(block, ActionType.BURN, before, BlockSnapshot.capture(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (!explosions) return;
        final List<Block> blocks = List.copyOf(event.blockList());
        final List<BlockSnapshot> before = blocks.stream().map(BlockSnapshot::capture).toList();
        if (blocks.isEmpty()) return;
        later(blocks.getFirst(), () -> {
            for (int i = 0; i < blocks.size(); i++) {
                final Block block = blocks.get(i);
                audit.recordEnvironment(block, ActionType.EXPLOSION, before.get(i), BlockSnapshot.capture(block));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (!explosions) return;
        final List<Block> blocks = List.copyOf(event.blockList());
        final List<BlockSnapshot> before = blocks.stream().map(BlockSnapshot::capture).toList();
        if (blocks.isEmpty()) return;
        later(blocks.getFirst(), () -> {
            for (int i = 0; i < blocks.size(); i++) {
                final Block block = blocks.get(i);
                audit.recordEntity(block, ActionType.EXPLOSION, event.getEntity(), before.get(i), BlockSnapshot.capture(block));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (!growth) return;
        recordAfter(event.getBlock(), ActionType.FORM, Actor.environment(), BlockSnapshot.capture(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (!growth) return;
        recordAfter(event.getBlock(), ActionType.FORM, Actor.environment(), BlockSnapshot.capture(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (!growth) return;
        recordAfter(event.getBlock(), ActionType.GROW, Actor.environment(), BlockSnapshot.capture(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (!growth) return;
        recordAfter(event.getBlock(), ActionType.SPREAD, Actor.environment(), BlockSnapshot.capture(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (!growth) return;
        final Actor actor = event.getPlayer() == null ? Actor.environment() : new Actor(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        final List<Block> blocks = event.getBlocks().stream().map(state -> state.getBlock()).toList();
        final List<BlockSnapshot> before = blocks.stream().map(BlockSnapshot::capture).toList();
        if (blocks.isEmpty()) return;
        later(blocks.getFirst(), () -> {
            for (int i = 0; i < blocks.size(); i++) audit.record(blocks.get(i), ActionType.GROW, actor, before.get(i), BlockSnapshot.capture(blocks.get(i)));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (!growth) return;
        final Actor actor = event.getPlayer() == null ? Actor.environment() : new Actor(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        final List<Block> blocks = event.getBlocks().stream().map(state -> state.getBlock()).toList();
        final List<BlockSnapshot> before = blocks.stream().map(BlockSnapshot::capture).toList();
        if (blocks.isEmpty()) return;
        later(blocks.getFirst(), () -> {
            for (int i = 0; i < blocks.size(); i++) audit.record(blocks.get(i), ActionType.GROW, actor, before.get(i), BlockSnapshot.capture(blocks.get(i)));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (!fluids) return;
        final Block destination = event.getToBlock();
        final BlockSnapshot before = BlockSnapshot.capture(destination);
        later(destination, () -> audit.recordEnvironment(destination, ActionType.FLUID, before, BlockSnapshot.capture(destination)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (!entityChanges) return;
        final Block block = event.getBlock();
        final BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.recordEntity(block, ActionType.ENTITY_CHANGE, event.getEntity(), before, BlockSnapshot.capture(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityForm(EntityBlockFormEvent event) {
        if (!entityChanges) return;
        final Block block = event.getBlock();
        final BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.recordEntity(block, ActionType.FORM, event.getEntity(), before, BlockSnapshot.capture(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (piston) recordPiston(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (piston) recordPiston(event.getBlocks(), event.getDirection().getOppositeFace());
    }

    private void recordPiston(List<Block> blocks, BlockFace direction) {
        if (blocks.isEmpty()) return;
        final List<Block> sources = List.copyOf(blocks);
        final List<Block> destinations = sources.stream().map(block -> block.getRelative(direction)).toList();
        final List<BlockSnapshot> sourceBefore = sources.stream().map(BlockSnapshot::capture).toList();
        final List<BlockSnapshot> destinationBefore = destinations.stream().map(BlockSnapshot::capture).toList();
        later(sources.getFirst(), () -> {
            for (int i = 0; i < sources.size(); i++) {
                final Block source = sources.get(i);
                final Block destination = destinations.get(i);
                audit.recordEnvironment(source, ActionType.PISTON, sourceBefore.get(i), BlockSnapshot.capture(source));
                audit.recordEnvironment(destination, ActionType.PISTON, destinationBefore.get(i), BlockSnapshot.capture(destination));
            }
        });
    }

    private void recordAfter(Block block, ActionType action, Actor actor, BlockSnapshot ignored) {
        final BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.record(block, action, actor, before, BlockSnapshot.capture(block)));
    }

    private void later(Block block, Runnable task) {
        Bukkit.getRegionScheduler().runDelayed(plugin, block.getWorld(), block.getChunk().getX(), block.getChunk().getZ(), ignored -> task.run(), 1L);
    }
}
