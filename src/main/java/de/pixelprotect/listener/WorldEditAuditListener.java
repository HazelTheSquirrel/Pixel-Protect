package de.pixelprotect.listener;

import com.google.common.eventbus.Subscribe;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import de.pixelprotect.model.Actor;
import de.pixelprotect.service.AuditService;
import org.bukkit.World;

import java.util.UUID;

/** Optional WorldEdit integration. Captures WorldEdit and FAWE-compatible edit sessions at the extent boundary. */
public final class WorldEditAuditListener {
    private final AuditService audit;

    public WorldEditAuditListener(AuditService audit) {
        this.audit = audit;
    }

    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
    }

    public void unregister() {
        try {
            WorldEdit.getInstance().getEventBus().unregister(this);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered by WorldEdit during shutdown.
        }
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != com.sk89q.worldedit.EditSession.Stage.BEFORE_HISTORY || event.getWorld() == null) return;
        UUID worldId;
        try {
            World world = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(event.getWorld());
            worldId = world.getUID();
        } catch (RuntimeException exception) {
            return;
        }
        Actor actor = actor(event.getActor());
        event.setExtent(new LoggingExtent(event.getExtent(), audit, worldId, actor));
    }

    private static Actor actor(com.sk89q.worldedit.extension.platform.Actor actor) {
        if (actor == null) return Actor.environment();
        try {
            UUID uuid = actor.getUniqueId();
            return new Actor(uuid, actor.getName());
        } catch (RuntimeException exception) {
            return Actor.environment();
        }
    }

    private static final class LoggingExtent extends AbstractDelegateExtent {
        private final AuditService audit;
        private final UUID worldId;
        private final Actor actor;
        private final UUID transactionId = UUID.randomUUID();
        private long sequence;

        private LoggingExtent(Extent extent, AuditService audit, UUID worldId, Actor actor) {
            super(extent);
            this.audit = audit;
            this.worldId = worldId;
            this.actor = actor;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location, T block) throws com.sk89q.worldedit.WorldEditException {
            BaseBlock before = getFullBlock(location);
            BaseBlock after = block.toBaseBlock();
            String beforeData;
            String afterData;
            try {
                beforeData = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(before).getAsString();
                afterData = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(after).getAsString();
            } catch (RuntimeException exception) {
                return super.setBlock(location, block);
            }
            boolean changed = !beforeData.equals(afterData);
            boolean result = super.setBlock(location, block);
            if (changed && result) {
                String details = nbtDetails(before, after);
                audit.recordWorldEdit(worldId, location.x(), location.y(), location.z(), actor,
                        beforeData, afterData, details, transactionId, sequence++);
            }
            return result;
        }

        private static String nbtDetails(BaseBlock before, BaseBlock after) {
            String beforeNbt = before.getNbt() == null ? "" : before.getNbt().toString();
            String afterNbt = after.getNbt() == null ? "" : after.getNbt().toString();
            if (beforeNbt.isEmpty() && afterNbt.isEmpty()) return "WORLD_EDIT";
            return "WORLD_EDIT;before_nbt=" + beforeNbt + ";after_nbt=" + afterNbt;
        }
    }
}
