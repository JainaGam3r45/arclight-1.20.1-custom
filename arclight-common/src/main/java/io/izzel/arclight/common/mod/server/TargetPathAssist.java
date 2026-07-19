package io.izzel.arclight.common.mod.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary chase assist for {@code /target path}: follow-range boost and moveTo refresh.
 * No teleports — mobs must walk with vanilla navigation.
 */
public final class TargetPathAssist {

    private static final int MAX_ACTIVE = 48;
    private static final int DURATION_TICKS = 300;
    private static final int NAV_REFRESH_TICKS = 20;
    private static final double ARRIVE_DIST_SQ = 9.0D;
    private static final double FOLLOW_RANGE_CAP = 64.0D;
    private static final double MOVE_SPEED = 1.15D;
    private static final UUID FOLLOW_MODIFIER_ID = UUID.fromString("a7c1d0e2-4b3f-4a9e-9c1d-2e8f0b6a5d43");
    private static final String FOLLOW_MODIFIER_NAME = "arclight_target_path";

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private TargetPathAssist() {
    }

    /**
     * @return {@code true} if the mob was enrolled in path assist
     */
    public static boolean enroll(Mob mob, ServerPlayer victim, int radius) {
        if (mob == null || victim == null || !mob.isAlive() || mob.isRemoved() || mob.level() != victim.level()) {
            return false;
        }
        if (SESSIONS.size() >= MAX_ACTIVE && !SESSIONS.containsKey(mob.getUUID())) {
            return false;
        }
        Session previous = SESSIONS.remove(mob.getUUID());
        if (previous != null) {
            previous.restoreFollowRange();
        }
        Session session = new Session(mob, victim, radius);
        session.applyFollowRange();
        session.refreshNavigation();
        SESSIONS.put(mob.getUUID(), session);
        return true;
    }

    public static int activeCount() {
        return SESSIONS.size();
    }

    /**
     * @return {@code true} if a path-assist session existed for this mob
     */
    public static boolean clearMob(Mob mob) {
        if (mob == null) {
            return false;
        }
        Session session = SESSIONS.remove(mob.getUUID());
        if (session == null) {
            return false;
        }
        session.restoreFollowRange();
        session.stopNavigation();
        return true;
    }

    /**
     * Clear path-assist sessions chasing this player.
     *
     * @return number of sessions removed
     */
    public static int clearFor(ServerPlayer victim) {
        if (victim == null) {
            return 0;
        }
        UUID victimId = victim.getUUID();
        int cleared = 0;
        Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Session session = it.next().getValue();
            if (victimId.equals(session.victimId)) {
                session.restoreFollowRange();
                session.stopNavigation();
                it.remove();
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * Clear every path-assist session and restore follow-range modifiers.
     *
     * @return number of sessions removed
     */
    public static int clearAll() {
        int cleared = SESSIONS.size();
        for (Session session : SESSIONS.values()) {
            session.restoreFollowRange();
            session.stopNavigation();
        }
        SESSIONS.clear();
        return cleared;
    }

    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Session session = it.next().getValue();
            if (!session.tick()) {
                session.restoreFollowRange();
                it.remove();
            }
        }
    }

    private static final class Session {
        private final Mob mob;
        private final UUID victimId;
        private final int radius;
        private int age;

        private Session(Mob mob, ServerPlayer victim, int radius) {
            this.mob = mob;
            this.victimId = victim.getUUID();
            this.radius = radius;
        }

        private boolean tick() {
            age++;
            if (age > DURATION_TICKS) {
                return false;
            }
            if (mob == null || mob.isRemoved() || !mob.isAlive()) {
                return false;
            }
            if (!(mob.level() instanceof ServerLevel level)) {
                return false;
            }
            MinecraftServer server = level.getServer();
            if (server == null) {
                return false;
            }
            ServerPlayer victim = server.getPlayerList().getPlayer(victimId);
            if (victim == null || victim.isRemoved() || !victim.isAlive() || mob.level() != victim.level()) {
                return false;
            }
            if (mob.getTarget() != victim) {
                return false;
            }
            if (mob.distanceToSqr(victim) <= ARRIVE_DIST_SQ) {
                return false;
            }

            if (age % NAV_REFRESH_TICKS == 0) {
                refreshNavigation(victim);
            }
            return true;
        }

        private void applyFollowRange() {
            AttributeInstance attr = mob.getAttribute(Attributes.FOLLOW_RANGE);
            if (attr == null) {
                return;
            }
            attr.removeModifier(FOLLOW_MODIFIER_ID);
            double desired = Math.min(FOLLOW_RANGE_CAP, Math.max(attr.getValue(), radius + 16.0D));
            double delta = desired - attr.getValue();
            if (delta > 0.01D) {
                attr.addTransientModifier(new AttributeModifier(
                    FOLLOW_MODIFIER_ID, FOLLOW_MODIFIER_NAME, delta, AttributeModifier.Operation.ADDITION));
            }
        }

        private void restoreFollowRange() {
            if (mob == null || mob.isRemoved()) {
                return;
            }
            AttributeInstance attr = mob.getAttribute(Attributes.FOLLOW_RANGE);
            if (attr != null) {
                attr.removeModifier(FOLLOW_MODIFIER_ID);
            }
        }

        private void refreshNavigation() {
            if (!(mob.level() instanceof ServerLevel level)) {
                return;
            }
            MinecraftServer server = level.getServer();
            if (server == null) {
                return;
            }
            ServerPlayer victim = server.getPlayerList().getPlayer(victimId);
            if (victim != null) {
                refreshNavigation(victim);
            }
        }

        private void refreshNavigation(ServerPlayer victim) {
            mob.getNavigation().moveTo(victim, MOVE_SPEED);
        }

        private void stopNavigation() {
            if (mob != null && !mob.isRemoved()) {
                mob.getNavigation().stop();
            }
        }
    }
}
