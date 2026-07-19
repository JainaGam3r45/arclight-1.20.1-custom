package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.mod.server.spawn.EntityGenerationManager;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.level.LocalMobCapCalculator$MobCounts")
public class LocalMobCapCalculator_MobCountsMixin {

    @Shadow
    private Object2IntMap<MobCategory> counts;

    @Inject(method = "canSpawn", at = @At("HEAD"), cancellable = true)
    private void arclight$configurableLocalCap(MobCategory category, CallbackInfoReturnable<Boolean> cir) {
        int current = this.counts.getOrDefault(category, 0);
        cir.setReturnValue(current < EntityGenerationManager.getLocalMobCap(category));
    }
}
