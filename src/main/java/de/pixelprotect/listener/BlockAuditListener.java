package de.pixelprotect.listener;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.Actor;
import de.pixelprotect.model.BlockSnapshot;
import de.pixelprotect.service.AuditService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Player;
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

/** Region-safe event capture; multi-block events retain one transaction id across all records. */
public final class BlockAuditListener implements Listener {
    private final Plugin plugin;
    private final AuditService audit;
    private final boolean placeBreak, explosions, fire, piston, fluids, growth, entityChanges;

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
        Block block = event.getBlock();
        BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.recordPlayer(block, ActionType.BREAK, event.getPlayer(), before, BlockSnapshot.capture(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!placeBreak || event instanceof BlockMultiPlaceEvent) return;
        Block block = event.getBlockPlaced();
        BlockSnapshot before = BlockSnapshot.fromState(event.getBlockReplacedState());
        later(block, () -> audit.recordPlayer(block, ActionType.PLACE, event.getPlayer(), before, BlockSnapshot.capture(block)));
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
        for (int i = 0; i < blocks.size(); i++) {
            int sequence = i;
            Block block = blocks.get(i);
            later(block, () -> audit.recordPlayer(block, ActionType.PLACE, event.getPlayer(), before.get(sequence),
                    BlockSnapshot.capture(block), transaction, sequence));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (!fire) return;
        Block block = event.getBlock();
        BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.recordEnvironment(block, ActionType.BURN, before, BlockSnapshot.capture(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (explosions) recordMany(event.blockList(), ActionType.EXPLOSION, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (explosions) recordMany(event.blockList(), ActionType.EXPLOSION, event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (growth) recordAfter(event.getBlock(), ActionType.FORM, Actor.environment());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (growth) recordAfter(event.getBlock(), ActionType.FORM, Actor.environment());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (growth) recordAfter(event.getBlock(), ActionType.GROW, Actor.environment());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (growth) recordAfter(event.getBlock(), ActionType.SPREAD, Actor.environment());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (!growth) return;
        Actor actor = event.getPlayer() == null ? Actor.environment() : new Actor(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        recordStates(event.getBlocks(), ActionType.GROW, actor);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (!growth) return;
        Actor actor = event.getPlayer() == null ? Actor.environment() : new Actor(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        recordStates(event.getBlocks(), ActionType.GROW, actor);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (!fluids || !isFluid(event.getBlock().getType())) return;
        Block block = event.getToBlock();
        BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.recordEnvironment(block, ActionType.FLUID, before, BlockSnapshot.capture(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (!entityChanges) return;
        Block block = event.getBlock();
        BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.recordEntity(block, ActionType.ENTITY_CHANGE, event.getEntity(), before, BlockSnapshot.capture(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityForm(EntityBlockFormEvent event) {
        if (!entityChanges) return;
        Block block = event.getBlock();
        BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.recordEntity(block, ActionType.FORM, event.getEntity(), before, BlockSnapshot.capture(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (piston) recordPiston(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (piston) recordPiston(event.getBlocks(), event.getDirection());
    }

    private static boolean isFluid(Material material) {
        return material == Material.WATER || material == Material.LAVA;
    }

    private void recordPiston(List<Block> moved, BlockFace direction) {
        if (moved.isEmpty()) return;
        Map<BlockKey, Block> affected = new LinkedHashMap<>();
        for (Block block : moved) {
            affected.putIfAbsent(new BlockKey(block), block);
            Block destination = block.getRelative(direction);
            affected.putIfAbsent(new BlockKey(destination), destination);
        }
        List<Block> blocks = List.copyOf(affected.values());
        UUID transaction = audit.newTransaction();
        List<BlockSnapshot> before = blocks.stream().map(BlockSnapshot::capture).toList();
        for (int i = 0; i < blocks.size(); i++) {
            int sequence = i;
            Block block = blocks.get(i);
            later(block, () -> audit.record(block, ActionType.PISTON, Actor.environment(), before.get(sequence),
                    BlockSnapshot.capture(block), transaction, sequence));
        }
    }

    private void recordMany(List<Block> input, ActionType action, org.bukkit.entity.Entity entity) {
        if (input.isEmpty()) return;
        Map<BlockKey, BlockSnapshot> before = new LinkedHashMap<>();
        Map<BlockKey, Block> blocks = new LinkedHashMap<>();
        for (Block block : input) {
            BlockKey key = new BlockKey(block);
            if (!before.containsKey(key)) {
                before.put(key, BlockSnapshot.capture(block));
                blocks.put(key, block);
            }
        }
        UUID transaction = audit.newTransaction();
        Actor playerCause = entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player
                ? new Actor(player.getUniqueId(), player.getName()) : Actor.environment();
        int sequence = 0;
        for (var entry : blocks.entrySet()) {
            Block block = entry.getValue();
            BlockSnapshot snapshot = before.get(entry.getKey());
            int current = sequence++;
            later(block, () -> audit.record(block, action, playerCause, snapshot, BlockSnapshot.capture(block), transaction, current));
        }
    }

    private void recordStates(List<? extends org.bukkit.block.BlockState> states, ActionType action, Actor actor) {
        if (states.isEmpty()) return;
        UUID transaction = audit.newTransaction();
        List<Block> blocks = states.stream().map(org.bukkit.block.BlockState::getBlock).toList();
        List<BlockSnapshot> before = states.stream().map(BlockSnapshot::fromState).toList();
        for (int i = 0; i < blocks.size(); i++) {
            int sequence = i;
            Block block = blocks.get(i);
            later(block, () -> audit.record(block, action, actor, before.get(sequence), BlockSnapshot.capture(block), transaction, sequence));
        }
    }

    private void recordAfter(Block block, ActionType action, Actor actor) {
        BlockSnapshot before = BlockSnapshot.capture(block);
        later(block, () -> audit.record(block, action, actor, before, BlockSnapshot.capture(block)));
    }

    private void later(Block block, Runnable task) {
        Bukkit.getRegionScheduler().runDelayed(plugin, block.getWorld(), block.getChunk().getX(), block.getChunk().getZ(), ignored -> task.run(), 1L);
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        BlockKey(Block block) { this(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    }
}
