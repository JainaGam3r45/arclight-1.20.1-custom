package io.izzel.arclight.common.mixin.core.world.level.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin<T extends EntityAccess> {

    // @formatter:off
    @Shadow public abstract void close() throws IOException;
    @Shadow @Final private EntityPersistentStorage<T> permanentStorage;
    @Shadow @Final EntitySectionStorage<T> sectionStorage;
    @Shadow @Final private Long2ObjectMap<PersistentEntitySectionManager.ChunkLoadStatus> chunkLoadStatuses;
    @Shadow @Final private Set<UUID> knownUuids;
    @Shadow @Final private static org.slf4j.Logger LOGGER;
    // @formatter:on

    @Unique private static final long ARCLIGHT_DUPLICATE_UUID_REPORT_INTERVAL_MS = 60_000L;
    @Unique private final Set<Long> arclight$duplicateEntityChunks = new HashSet<>();
    @Unique private boolean arclight$processingPendingLoads;
    @Unique private int arclight$discardedDuplicateEntities;
    @Unique private long arclight$lastDuplicateUuidReport;

    public void close(boolean save) throws IOException {
        if (save) {
            this.close();
        } else {
            this.permanentStorage.close();
        }
    }

    public List<Entity> getEntities(ChunkPos chunkCoordIntPair) {
        return sectionStorage.getExistingSectionsInChunk(chunkCoordIntPair.toLong())
            .flatMap(EntitySection::getEntities).map(o -> (Entity) o).collect(Collectors.toList());
    }

    public boolean isPending(long cord) {
        return this.chunkLoadStatuses.get(cord) == PersistentEntitySectionManager.ChunkLoadStatus.PENDING;
    }

    @Inject(method = "processPendingLoads", at = @At("HEAD"))
    private void arclight$beginDuplicateUuidCleanup(CallbackInfo ci) {
        this.arclight$processingPendingLoads = true;
    }

    @Inject(method = "m_157557_", at = @At("HEAD"), cancellable = true, remap = false)
    private void arclight$regenerateDuplicateUuid(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (!this.arclight$processingPendingLoads || !(entity instanceof Entity minecraftEntity) || !this.knownUuids.contains(entity.getUUID())) {
            return;
        }
        UUID uuid;
        do {
            uuid = UUID.randomUUID();
        } while (this.knownUuids.contains(uuid));
        minecraftEntity.setUUID(uuid);
        this.knownUuids.add(uuid);
        this.arclight$duplicateEntityChunks.add(ChunkPos.asLong(entity.blockPosition()));
        this.arclight$discardedDuplicateEntities++;
        cir.setReturnValue(true);
    }

    @Inject(method = "processPendingLoads", at = @At("TAIL"))
    private void arclight$cleanDuplicateUuidChunks(CallbackInfo ci) {
        this.arclight$processingPendingLoads = false;
        for (long chunk : this.arclight$duplicateEntityChunks) {
            this.arclight$storeEntitiesWithoutDuplicates(chunk);
        }
        this.arclight$duplicateEntityChunks.clear();
        this.arclight$reportDuplicateUuids(false);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void arclight$reportPendingDuplicateUuids(CallbackInfo ci) {
        this.arclight$reportDuplicateUuids(true);
    }

    @Unique
    private void arclight$storeEntitiesWithoutDuplicates(long chunk) {
        List<T> entities = this.sectionStorage.getExistingSectionsInChunk(chunk)
            .flatMap(EntitySection::getEntities)
            .collect(Collectors.toList());
        this.permanentStorage.storeEntities(new ChunkEntities<>(new ChunkPos(chunk), entities));
    }

    @Unique
    private void arclight$reportDuplicateUuids(boolean force) {
        if (this.arclight$discardedDuplicateEntities == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - this.arclight$lastDuplicateUuidReport < ARCLIGHT_DUPLICATE_UUID_REPORT_INTERVAL_MS) {
            return;
        }
        LOGGER.info("Regenerated {} duplicate entity UUIDs while loading chunks", this.arclight$discardedDuplicateEntities);
        this.arclight$discardedDuplicateEntities = 0;
        this.arclight$lastDuplicateUuidReport = now;
    }

    @Unique private boolean arclight$fireEvent = false;

    @Inject(method = "storeChunkSections", locals = LocalCapture.CAPTURE_FAILHARD,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityPersistentStorage;storeEntities(Lnet/minecraft/world/level/entity/ChunkEntities;)V"))
    private void arclight$fireUnload(long pos, Consumer<T> consumer, CallbackInfoReturnable<Boolean> cir, @Coerce Object status, List<T> list) {
        if (arclight$fireEvent) {
            CraftEventFactory.callEntitiesUnloadEvent(((EntityStorage) permanentStorage).level, new ChunkPos(pos),
                list.stream().map(entity -> (Entity) entity).collect(Collectors.toList()));
        }
    }

    @Inject(method = "storeChunkSections", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityPersistentStorage;storeEntities(Lnet/minecraft/world/level/entity/ChunkEntities;)V"))
    private void arclight$resetFlag(long pos, Consumer<T> consumer, CallbackInfoReturnable<Boolean> cir) {
        arclight$fireEvent = false;
    }

    @Inject(method = "processChunkUnload", at = @At("HEAD"))
    private void arclight$fireEvent(long pChunkPosValue, CallbackInfoReturnable<Boolean> cir) {
        arclight$fireEvent = true;
    }

    @Inject(method = "processPendingLoads", locals = LocalCapture.CAPTURE_FAILHARD, at = @At(value = "INVOKE", shift = At.Shift.AFTER, remap = false, target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;"))
    private void arclight$fireLoad(CallbackInfo ci, ChunkEntities<T> chunkEntities) {
        List<Entity> entities = getEntities(chunkEntities.getPos());
        CraftEventFactory.callEntitiesLoadEvent(((EntityStorage) permanentStorage).level, chunkEntities.getPos(), entities);
    }
}
