package io.izzel.arclight.common.mod.server.spawn;

import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.bridge.core.world.server.ChunkMapBridge;
import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.conf.SpawnSpec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Main-thread budget for natural mob generation. Caps spawn-scan CPU under MSPT pressure
 * without Folia-style region threading. Block tickers and spawners are untouched.
 */
public final class EntityGenerationManager {

    private static final ThreadLocal<WorldBudget> BUDGET = new ThreadLocal<>();

    private EntityGenerationManager() {
    }

    public static void beginWorldTick(ServerLevel level) {
        SpawnSpec spec = ArclightConfig.spec().getOptimization().getSpawn();
        if (!spec.isEnabled()) {
            BUDGET.remove();
            return;
        }
        BUDGET.set(new WorldBudget(level, spec, estimateMspt(level.getServer())));
    }

    public static void endWorldTick() {
        BUDGET.remove();
    }

    /**
     * @return {@code true} if {@code NaturalSpawner.spawnForChunk} may run for this chunk
     */
    public static boolean tryConsumeNaturalSpawn(ServerLevel level, LevelChunk chunk) {
        SpawnSpec spec = ArclightConfig.spec().getOptimization().getSpawn();
        if (!spec.isEnabled()) {
            return true;
        }
        WorldBudget budget = BUDGET.get();
        if (budget == null || budget.level != level) {
            beginWorldTick(level);
            budget = BUDGET.get();
            if (budget == null) {
                return true;
            }
        }
        if (budget.skipAll) {
            return false;
        }
        if (spec.isUseMobSpawnRange()) {
            var chunkMap = ((ServerChunkCache) level.getChunkSource()).chunkMap;
            if (!((ChunkMapBridge) chunkMap).bridge$anyPlayerCloseEnoughForSpawning(chunk.getPos(), true)) {
                return false;
            }
        }
        long now = System.nanoTime();
        if (budget.chunksConsumed >= spec.getMaxChunksPerTick()) {
            return false;
        }
        double spentMs = (now - budget.startNanos) / 1_000_000.0D;
        if (spentMs >= spec.getMaxMsPerTick()) {
            return false;
        }
        budget.chunksConsumed++;
        return true;
    }

    public static boolean isPerPlayerMobSpawns() {
        return ArclightConfig.spec().getOptimization().getSpawn().isPerPlayerMobSpawns();
    }

    private static double estimateMspt(MinecraftServer server) {
        if (server == null) {
            return 50.0D;
        }
        try {
            // Mojang 1.20.1: smoothed average tick time in milliseconds
            var method = MinecraftServer.class.getMethod("getAverageTickTime");
            Object value = method.invoke(server);
            if (value instanceof Number number && number.floatValue() > 0.0F) {
                return number.doubleValue();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        double tps = ((MinecraftServerBridge) server).bridge$getRecentTps();
        if (tps <= 0.0D) {
            return 50.0D;
        }
        return 1000.0D / tps;
    }

    private static final class WorldBudget {
        private final ServerLevel level;
        private final long startNanos;
        private final boolean skipAll;
        private int chunksConsumed;

        private WorldBudget(ServerLevel level, SpawnSpec spec, double mspt) {
            this.level = level;
            this.startNanos = System.nanoTime();
            this.skipAll = mspt >= spec.getSkipWhenMsptAbove();
            this.chunksConsumed = 0;
        }
    }
}
