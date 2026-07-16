package io.izzel.arclight.common.mixin.optimization.general.activationrange;

import io.izzel.arclight.common.bridge.optimization.EntityBridge_ActivationRange;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spigotmc.ActivationRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerWorldMixin_ActivationRange {

    // @formatter:off
    @Shadow private void tickPassenger(Entity vehicle, Entity passenger) {}
    // @formatter:on

    @Unique
    private static final boolean arclight$applyInactive = ArclightConfig.spec().getOptimization().useActivationAndTrackingRange();

    @Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;entityTickList:Lnet/minecraft/world/level/entity/EntityTickList;"))
    private void activationRange$activateEntity(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ActivationRange.activateEntities((ServerLevel) (Object) this);
    }

    @Inject(method = "tickNonPassenger", cancellable = true, at = @At(value = "HEAD"))
    private void activationRange$inactiveTick(Entity entityIn, CallbackInfo ci) {
        if (!arclight$applyInactive || ActivationRange.checkIfActive(entityIn)) {
            return;
        }
        // Mirror the cheap parts of tickNonPassenger without AI / full entity tick.
        entityIn.setOldPosAndRot();
        ++entityIn.tickCount;
        if (entityIn.canUpdate()) {
            ((EntityBridge_ActivationRange) entityIn).bridge$inactiveTick();
        }
        // Keep passenger ride ticks even if the root somehow became inactive (immunity usually prevents this).
        for (Entity passenger : entityIn.getPassengers()) {
            this.tickPassenger(entityIn, passenger);
        }
        ci.cancel();
    }
}
