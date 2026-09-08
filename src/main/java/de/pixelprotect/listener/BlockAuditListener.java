package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Region-safe block audit listener for Paper/Folia region execution. */
public final class BlockAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;
    private final boolean placeBreak, explosions, fire, piston, fluids, growth, entityChanges;
    private final Map<Object, BlockSnapshot> singleBefore = new ConcurrentHashMap<>();
    private final Map<Object, List<Block>> multiBlocks = new ConcurrentHashMap<>();
    private final Map<Object, List<BlockSnapshot>> multiBefore = new ConcurrentHashMap<>();

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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBreakBefore(BlockBreakEvent event) {
        if (placeBreak) singleBefore.put(event, BlockSnapshot.capture(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBreakAfter(BlockBreakEvent event) {
        if (!placeBreak) return;
        BlockSnapshot before = singleBefore.remove(event);
        if (before == null) return;
        BlockSnapshot after = BlockSnapshot.capture(event.getBlock());
        if (!before.blockData().equals(after.blockData()))
            audit.recordPlayer(event.getBlock(), ActionType.BREAK, event.getPlayer(), before, after);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!placeBreak || event instanceof BlockMultiPlaceEvent) return;
        Block block = event.getBlockPlaced();
        audit.recordPlayer(block, ActionType.PLACE, event.getPlayer(), BlockSnapshot.fromState(event.getBlockReplacedState()), BlockSnapshot.capture(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        if (!placeBreak) return;
        List<Block> blocks = new ArrayList<>();
        List<BlockSnapshot> before = new ArrayList<>();
        for (var state : event.getReplacedBlockStates()) {
            blocks.add(state.getBlock());
            before.add(BlockSnapshot.fromState(state));
        }
        if (blocks.isEmpty()) return;
        UUID transaction = audit.newTransaction();
        for (int i = 0; i < blocks.size(); i++)
            audit.recordPlayer(blocks.get(i), ActionType.PLACE, event.getPlayer(), before.get(i), BlockSnapshot.capture(blocks.get(i)), transaction, i);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBurnBefore(BlockBurnEvent event) { if (fire) singleBefore.put(event, BlockSnapshot.capture(event.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBurnAfter(BlockBurnEvent event) { if (fire) finishSingle(event, event.getBlock(), ActionType.BURN, Actor.environment()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockExplosionBefore(BlockExplodeEvent event) { if (explosions) captureMany(event, event.blockList()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockExplosionAfter(BlockExplodeEvent event) { if (explosions) finishMany(event, Actor.environment()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityExplosionBefore(EntityExplodeEvent event) { if (explosions) captureMany(event, event.blockList()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityExplosionAfter(EntityExplodeEvent event) {
        if (!explosions) return;
        Actor actor = event.getEntity() instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player
                ? new Actor(player.getUniqueId(), player.getName()) : Actor.environment();
        finishMany(event, actor);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onFadeBefore(BlockFadeEvent event) { if (growth) singleBefore.put(event, BlockSnapshot.capture(event.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFadeAfter(BlockFadeEvent event) { if (growth) finishSingle(event, event.getBlock(), ActionType.FORM, Actor.environment()); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onFormBefore(BlockFormEvent event) { if (growth) singleBefore.put(event, BlockSnapshot.capture(event.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFormAfter(BlockFormEvent event) { if (growth) finishSingle(event, event.getBlock(), ActionType.FORM, Actor.environment()); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onGrowBefore(BlockGrowEvent event) { if (growth) singleBefore.put(event, BlockSnapshot.capture(event.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onGrowAfter(BlockGrowEvent event) { if (growth) finishSingle(event, event.getBlock(), ActionType.GROW, Actor.environment()); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSpreadBefore(BlockSpreadEvent event) { if (growth) singleBefore.put(event, BlockSnapshot.capture(event.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSpreadAfter(BlockSpreadEvent event) { if (growth) finishSingle(event, event.getBlock(), ActionType.SPREAD, Actor.environment()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (!growth || event.getBlocks().isEmpty()) return;
        Actor actor = event.getPlayer() == null ? Actor.environment() : new Actor(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        recordStates(event.getBlocks(), ActionType.GROW, actor);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (!growth || event.getBlocks().isEmpty()) return;
        Actor actor = event.getPlayer() == null ? Actor.environment() : new Actor(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        recordStates(event.getBlocks(), ActionType.GROW, actor);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onFluidBefore(BlockFromToEvent event) {
        if (fluids && isFluid(event.getBlock().getType())) singleBefore.put(event, BlockSnapshot.capture(event.getToBlock()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFluidAfter(BlockFromToEvent event) {
        if (!fluids || !isFluid(event.getBlock().getType())) return;
        BlockSnapshot before = singleBefore.remove(event);
        if (before != null) audit.recordEnvironment(event.getToBlock(), ActionType.FLUID, before, BlockSnapshot.capture(event.getToBlock()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityChangeBefore(EntityChangeBlockEvent event) { if (entityChanges) singleBefore.put(event, BlockSnapshot.capture(event.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityChangeAfter(EntityChangeBlockEvent event) {
        if (!entityChanges) return;
        BlockSnapshot before = singleBefore.remove(event);
        if (before != null) audit.recordEntity(event.getBlock(), ActionType.ENTITY_CHANGE, event.getEntity(), before, BlockSnapshot.capture(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityFormBefore(EntityBlockFormEvent event) { if (entityChanges) singleBefore.put(event, BlockSnapshot.capture(event.getBlock())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityFormAfter(EntityBlockFormEvent event) {
        if (!entityChanges) return;
        BlockSnapshot before = singleBefore.remove(event);
        if (before != null) audit.recordEntity(event.getBlock(), ActionType.FORM, event.getEntity(), before, BlockSnapshot.capture(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPistonExtendBefore(BlockPistonExtendEvent event) { if (piston) capturePiston(event, event.getBlocks(), event.getDirection()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPistonExtendAfter(BlockPistonExtendEvent event) { if (piston) finishPiston(event); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPistonRetractBefore(BlockPistonRetractEvent event) { if (piston) capturePiston(event, event.getBlocks(), event.getDirection()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPistonRetractAfter(BlockPistonRetractEvent event) { if (piston) finishPiston(event); }

    private void capturePiston(Object event, List<Block> moved, BlockFace direction) {
        Map<BlockKey, Block> affected = new LinkedHashMap<>();
        for (Block block : moved) {
            affected.putIfAbsent(new BlockKey(block), block);
            Block adjacent = block.getRelative(direction);
            affected.putIfAbsent(new BlockKey(adjacent), adjacent);
        }
        List<Block> blocks = List.copyOf(affected.values());
        multiBlocks.put(event, blocks);
        multiBefore.put(event, blocks.stream().map(BlockSnapshot::capture).toList());
    }

    private void finishPiston(Object event) {
        List<Block> blocks = multiBlocks.remove(event);
        List<BlockSnapshot> before = multiBefore.remove(event);
        if (blocks == null || before == null) return;
        UUID transaction = audit.newTransaction();
        for (int i = 0; i < blocks.size(); i++) audit.record(blocks.get(i), ActionType.PISTON, Actor.environment(), before.get(i), BlockSnapshot.capture(blocks.get(i)), transaction, i);
    }

    private void captureMany(Object event, List<Block> input) {
        Map<BlockKey, Block> unique = new LinkedHashMap<>();
        for (Block block : input) unique.putIfAbsent(new BlockKey(block), block);
        List<Block> blocks = List.copyOf(unique.values());
        multiBlocks.put(event, blocks);
        multiBefore.put(event, blocks.stream().map(BlockSnapshot::capture).toList());
    }

    private void finishMany(Object event, Actor actor) {
        List<Block> blocks = multiBlocks.remove(event);
        List<BlockSnapshot> before = multiBefore.remove(event);
        if (blocks == null || before == null) return;
        UUID transaction = audit.newTransaction();
        for (int i = 0; i < blocks.size(); i++) audit.record(blocks.get(i), ActionType.EXPLOSION, actor, before.get(i), BlockSnapshot.capture(blocks.get(i)), transaction, i);
    }

    private void finishSingle(Object event, Block block, ActionType action, Actor actor) {
        BlockSnapshot before = singleBefore.remove(event);
        if (before == null) return;
        BlockSnapshot after = BlockSnapshot.capture(block);
        if (!before.blockData().equals(after.blockData()) || before.blockEntity() != null || after.blockEntity() != null)
            audit.record(block, action, actor, before, after);
    }

    private void recordStates(List<? extends org.bukkit.block.BlockState> states, ActionType action, Actor actor) {
        UUID transaction = audit.newTransaction();
        List<Block> blocks = states.stream().map(org.bukkit.block.BlockState::getBlock).toList();
        List<BlockSnapshot> before = states.stream().map(BlockSnapshot::fromState).toList();
        for (int i = 0; i < blocks.size(); i++) audit.record(blocks.get(i), action, actor, before.get(i), BlockSnapshot.capture(blocks.get(i)), transaction, i);
    }

    private static boolean isFluid(Material material) { return material == Material.WATER || material == Material.LAVA; }
    private record BlockKey(UUID world, int x, int y, int z) { BlockKey(Block block) { this(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); } }
}
