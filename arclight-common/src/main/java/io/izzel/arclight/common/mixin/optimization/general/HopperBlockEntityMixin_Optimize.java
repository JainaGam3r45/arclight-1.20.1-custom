package io.izzel.arclight.common.mixin.optimization.general;

import io.izzel.arclight.common.bridge.core.world.WorldBridge;
import io.izzel.arclight.common.mod.util.DistValidate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Conservative hopper early-outs. InventoryMoveItemEvent stays on the core transfer path;
 * these only skip work when there is clearly nothing to move / nowhere to look.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin_Optimize {

    /**
     * Spigot {@code hopper-can-load-chunks}: do not force-load chunks just to look for containers.
     * Returning null means no transfer, so InventoryMoveItemEvent is not fired (nothing moved).
     */
    @Inject(method = "getContainerAt(Lnet/minecraft/world/level/Level;DDD)Lnet/minecraft/world/Container;", cancellable = true, at = @At("HEAD"))
    private static void arclight$skipUnloadedChunk(Level level, double x, double y, double z, CallbackInfoReturnable<Container> cir) {
        if (!DistValidate.isValid(level)) {
            return;
        }
        var config = ((WorldBridge) level).bridge$spigotConfig();
        if (config != null && !config.hopperCanLoadChunks && !level.hasChunkAt(BlockPos.containing(x, y, z))) {
            cir.setReturnValue(null);
        }
    }
}
