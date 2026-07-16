package io.izzel.arclight.common.mixin.optimization.general;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin_Optimize {

    // @formatter:off
    @Shadow protected abstract void doPush(Entity entity);
    @Shadow public abstract boolean isPushable();
    // @formatter:on

    @Unique
    private int arclight$numCollisions;

    /**
     * @author Arclight
     * @reason Cap entity-entity push collisions (Spigot/Paper-style) and honor Bukkit collidable flags.
     */
    @Overwrite
    protected void pushEntities() {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) {
            self.level().getEntities(EntityTypeTest.forClass(Player.class), self.getBoundingBox(), EntitySelector.pushableBy(self)).forEach(this::doPush);
            return;
        }

        // Bukkit LivingEntity#setCollidable(false) / exemptions flow through isPushable().
        if (!this.isPushable()) {
            return;
        }

        int maxCollisions = ArclightConfig.spec().getOptimization().getMaxEntityCollisions();
        // Paper-style: 0 (or less) disables entity push collisions entirely.
        if (maxCollisions <= 0) {
            int cramming = self.level().getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
            if (cramming <= 0) {
                return;
            }
        }

        List<Entity> list = self.level().getEntities(self, self.getBoundingBox(), EntitySelector.pushableBy(self));
        if (list.isEmpty()) {
            return;
        }

        int cramming = self.level().getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        if (cramming > 0 && list.size() > cramming - 1 && self.getRandom().nextInt(4) == 0) {
            int stacked = 0;
            for (Entity entity : list) {
                if (!entity.isPassenger()) {
                    ++stacked;
                }
            }
            if (stacked > cramming - 1) {
                self.hurt(self.damageSources().cramming(), 6.0F);
            }
        }

        if (maxCollisions <= 0) {
            return;
        }

        this.arclight$numCollisions = Math.max(0, this.arclight$numCollisions - maxCollisions);
        for (Entity entity : list) {
            if (this.arclight$numCollisions >= maxCollisions) {
                break;
            }
            // Respect Bukkit LivingEntity#setCollidable / exemptions for this entity.
            if (!self.canCollideWith(entity)) {
                continue;
            }
            ++this.arclight$numCollisions;
            this.doPush(entity);
        }
    }
}
