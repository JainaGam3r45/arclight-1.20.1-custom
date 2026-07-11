package io.izzel.arclight.common.mixin.bukkit;

import org.bukkit.craftbukkit.v.map.CraftMapRenderer;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CraftMapRenderer.class, remap = false)
public abstract class CraftMapRendererMixin {

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lorg/bukkit/map/MapCursorCollection;addCursor(IIBBZLjava/lang/String;)Lorg/bukkit/map/MapCursor;"))
    private MapCursor arclight$skipUnsupportedCursor(MapCursorCollection cursors, int x, int y, byte direction, byte type, boolean visible, String caption) {
        return type >= 0 && type <= 26 ? cursors.addCursor(x, y, direction, type, visible, caption) : null;
    }
}
