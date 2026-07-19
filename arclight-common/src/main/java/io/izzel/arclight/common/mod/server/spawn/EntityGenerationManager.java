package io.izzel.arclight.common.mod.server.spawn;

import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.bridge.core.world.server.ChunkMapBridge;
import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.conf.LocalMobCapSpec;
import io.izzel.arclight.i18n.conf.SpawnSpec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
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
        double mspt = estimateMspt(level.getServer());
        int players = level.getServer() != null ? level.getServer().getPlayerCount() : level.players().size();
        BUDGET.set(new WorldBudget(level, spec, mspt, players));
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
        if (!budget.relaxed) {
            if (budget.chunksConsumed >= spec.getMaxChunksPerTick()) {
                return false;
            }
            if (budget.spawnNanos / 1_000_000.0D >= spec.getMaxMsPerTick()) {
                return false;
            }
        }
        budget.chunksConsumed++;
        return true;
    }

    /**
     * Charge only time spent inside {@code NaturalSpawner.spawnForChunk}, not tickChunks prep.
     */
    public static void recordSpawnWork(long nanos) {
        WorldBudget budget = BUDGET.get();
        if (budget != null && nanos > 0L) {
            budget.spawnNanos += nanos;
        }
    }

    public static boolean isPerPlayerMobSpawns() {
        return ArclightConfig.spec().getOptimization().getSpawn().isPerPlayerMobSpawns();
    }

    /**
     * Effective local density cap for {@code LocalMobCapCalculator.MobCounts}.
     * Absolute override when {@code >= 0}; otherwise {@code max(1, round(vanilla * scale))}.
     */
    public static int getLocalMobCap(MobCategory category) {
        int vanilla = category.getMaxInstancesPerChunk();
        if (category == MobCategory.MISC) {
            return vanilla;
        }
        SpawnSpec spec = ArclightConfig.spec().getOptimization().getSpawn();
        LocalMobCapSpec overrides = spec.getLocalMobCap();
        int override = overrides.getOverride(category.getName());
        if (override >= 0) {
            return override;
        }
        double scale = spec.getLocalMobCapScale();
        if (scale == 1.0D) {
            return Math.max(1, vanilla);
        }
        return Math.max(1, (int) Math.round(vanilla * scale));
    }

    public static double estimateMspt(MinecraftServer server) {
        if (server == null) {
            return 50.0D;
        }
        float avg = server.getAverageTickTime();
        if (avg > 0.0F) {
            return avg;
        }
        double tps = ((MinecraftServerBridge) server).bridge$getRecentTps();
        if (tps <= 0.0D) {
            return 50.0D;
        }
        // Healthy TPS must not map to 1000/20=50 (that is the full tick budget, not MSPT).
        if (tps >= 19.5D) {
            return 25.0D;
        }
        return 1000.0D / tps;
    }

    public static String describeMode(MinecraftServer server) {
        SpawnSpec spec = ArclightConfig.spec().getOptimization().getSpawn();
        if (!spec.isEnabled()) {
            return "disabled";
        }
        double mspt = estimateMspt(server);
        int players = server != null ? server.getPlayerCount() : 0;
        if (mspt >= spec.getSkipWhenMsptAbove()) {
            return "skip (mspt)";
        }
        if (players <= spec.getRelaxedWhenPlayersAtMost() && mspt < spec.getRelaxedWhenMsptBelow()) {
            return "relaxed";
        }
        return "budgeted";
    }

    private static final class WorldBudget {
        private final ServerLevel level;
        private final boolean skipAll;
        private final boolean relaxed;
        private int chunksConsumed;
        private long spawnNanos;

        private WorldBudget(ServerLevel level, SpawnSpec spec, double mspt, int players) {
            this.level = level;
            this.skipAll = mspt >= spec.getSkipWhenMsptAbove();
            this.relaxed = !this.skipAll
                && players <= spec.getRelaxedWhenPlayersAtMost()
                && mspt < spec.getRelaxedWhenMsptBelow();
            this.chunksConsumed = 0;
            this.spawnNanos = 0L;
        }
    }
}
