package io.izzel.arclight.common.mixin.optimization.dfu;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DataFixerBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(DataFixers.class)
public class DataFixesManagerMixin_DFU {

    @Inject(method = "createFixerUpper", cancellable = true, at = @At("HEAD"))
    private static void arclight$disableDfu(Set<DSL.TypeReference> types, CallbackInfoReturnable<DataFixer> cir) {
        cir.setReturnValue(new DataFixerBuilder(SharedConstants.getCurrentVersion().getDataVersion().getVersion()).buildUnoptimized());
    }
}
