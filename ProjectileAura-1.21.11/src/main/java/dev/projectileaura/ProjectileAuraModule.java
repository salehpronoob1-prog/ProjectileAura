package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Set;

/** ProjectileAura 0.3: closest-first projectile combat with periodic recovery scan. */
public class ProjectileAuraModule extends Module {
    private final SettingGroup physics = settings.getDefaultGroup();
    private final SettingGroup targeting = settings.createGroup("Targeting");
    private final SettingGroup rotation = settings.createGroup("Rotation");
    private final SettingGroup attack = settings.createGroup("Attack");

    // Slider ranges are for quick adjustment. Typed numeric values may extend
    // beyond those ranges, subject to physical safety checks in Ballistics.
    private final Setting<Double> speed = physics.add(new DoubleSetting.Builder()
        .name("arrow-speed").description("Arrow launch speed in blocks/tick. Precise values can be typed.")
        .defaultValue(1.5).min(0.0001).sliderRange(0.1, 10.0)
        .decimalPlaces(5).onSliderRelease().build());

    private final Setting<Double> gravity = physics.add(new DoubleSetting.Builder()
        .name("gravity").description("Gravity per tick. Vanilla arrows typically use 0.05.")
        .defaultValue(0.05).sliderRange(-0.1, 0.3)
        .decimalPlaces(5).onSliderRelease().build());

    private final Setting<Double> drag = physics.add(new DoubleSetting.Builder()
        .name("drag").description("Velocity retention per tick. Vanilla arrows use about 0.99.")
        .defaultValue(0.99).min(0.0001).sliderRange(0.8, 1.2)
        .decimalPlaces(6).onSliderRelease().build());

    private final Setting<Set<EntityType<?>>> entities = targeting.add(new EntityTypeListSetting.Builder()
        .name("entities").description("Select the types of entities to target.")
        .onlyAttackable().defaultValue(EntityType.SKELETON).build());

    private final Setting<String> nameContains = targeting.add(new StringSetting.Builder()
        .name("name-contains").description("Optional, case-insensitive target name filter.")
        .defaultValue("").build());

    private final Setting<Double> range = targeting.add(new DoubleSetting.Builder()
        .name("target-range").description("Maximum target distance; type numbers beyond slider range.")
        .defaultValue(24).min(0.1).sliderRange(1, 128).decimalPlaces(2)
        .onSliderRelease().build());

    private final Setting<Double> aimHeight = targeting.add(new DoubleSetting.Builder()
        .name("aim-height").description("Aim at a fraction of target height (0 feet, 1 head).")
        .defaultValue(0.65).sliderRange(0, 1).decimalPlaces(4)
        .onSliderRelease().build());

    private final Setting<Double> extraHeight = targeting.add(new DoubleSetting.Builder()
        .name("vertical-offset").description("Additional vertical offset in blocks.")
        .defaultValue(0).sliderRange(-2, 2).decimalPlaces(4)
        .onSliderRelease().build());

    private final Setting<Boolean> lead = targeting.add(new BoolSetting.Builder()
        .name("lead-moving-targets").description("Lead moving targets using smoothed entity velocity.")
        .defaultValue(true).build());

    private final Setting<Boolean> requireSight = targeting.add(new BoolSetting.Builder()
        .name("require-line-of-sight").description("Avoid locking onto entities hidden behind walls.")
        .defaultValue(true).build());

    private final Setting<Boolean> aim = rotation.add(new BoolSetting.Builder()
        .name("auto-aim").description("Turn toward the calculated projectile intercept.")
        .defaultValue(true).build());

    private final Setting<Double> rotationSpeed = rotation.add(new DoubleSetting.Builder()
        .name("rotation-speed").description("Maximum degrees per tick; 180 is almost instant.")
        .defaultValue(12).min(0.01).sliderRange(1, 180).decimalPlaces(2)
        .onSliderRelease().build());

    private final Setting<Boolean> autoFire = attack.add(new BoolSetting.Builder()
        .name("auto-fire").description("Automatically left-click when correctly aimed at the nearest valid enemy.")
        .defaultValue(true).build());

    private final Setting<Boolean> fireInGuis = attack.add(new BoolSetting.Builder()
        .name("fire-in-guis")
        .description("Keep firing while inventory and other client screens are open. Can interact with menus depending on the server.")
        .defaultValue(true).visible(autoFire::get).build());

    private final Setting<Integer> fireInterval = attack.add(new IntSetting.Builder()
        .name("fire-interval-ticks").description("Minimum client ticks between left clicks (20 ticks = about 1 second).")
        .defaultValue(5).min(1).sliderRange(1, 40)
        .visible(autoFire::get).build());

    private final Setting<Double> fireTolerance = attack.add(new DoubleSetting.Builder()
        .name("fire-aim-tolerance").description("Maximum yaw and pitch error, in degrees, before firing.")
        .defaultValue(2.0).min(0.01).sliderRange(0.25, 15.0)
        .decimalPlaces(2).onSliderRelease().visible(autoFire::get).build());

    private static volatile long lastAutoFireNanos;

    public static long getLastAutoFireNanos() {
        return lastAutoFireNanos;
    }

    public boolean matchesSelectedEntity(LivingEntity entity) {
        if (entity == null || !entity.isAlive() || !entities.get().contains(entity.getType())) return false;
        String filter = nameContains.get().trim().toLowerCase(Locale.ROOT);
        return filter.isEmpty() || entity.getName().getString().toLowerCase(Locale.ROOT).contains(filter);
    }

    private static final long SAFETY_RESCAN_NANOS = 30_000_000_000L;
    private long nextSafetyRescan;
    private int fireTimer;
    private LivingEntity lockedTarget;
    private Vec3 smoothedMotion = Vec3.ZERO;

    public ProjectileAuraModule() {
        super(ProjectileAuraAddon.CATEGORY, "projectile-aura",
            "Stable aiming for custom arrow weapons, with adjustable projectile physics.");
    }

    @Override public void onActivate() {
        resetTarget();
        nextSafetyRescan = System.nanoTime() + SAFETY_RESCAN_NANOS;
    }

    @Override public void onDeactivate() { resetTarget(); }

    private void resetTarget() {
        lockedTarget = null;
        smoothedMotion = Vec3.ZERO;
        fireTimer = 0;
    }

    private boolean validTarget(LivingEntity entity, String filter, double limitSquared) {
        if (entity == mc.player || !entity.isAlive() || !entities.get().contains(entity.getType())) return false;
        if (mc.player.distanceToSqr(entity) > limitSquared) return false;
        if (!filter.isEmpty() && !entity.getName().getString().toLowerCase(Locale.ROOT).contains(filter)) return false;
        if (entity instanceof Player player && (player.isCreative() || !Friends.get().shouldAttack(player))) return false;
        return !requireSight.get() || PlayerUtils.canSeeEntity(entity);
    }

    /**
     * Always scans ALL loaded/renderable candidates to select the closest valid entity.
     * Using squared distance avoids unnecessary square roots and ensures consistent order.
     */
    private LivingEntity selectClosestTarget(String filter, double limitSquared) {
        LivingEntity nearest = null;
        double best = Double.POSITIVE_INFINITY;

        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !validTarget(living, filter, limitSquared)) continue;

            double distance = mc.player.distanceToSqr(living);
            if (!Double.isFinite(distance)) continue;

            // Deterministic tie-break to avoid toggling rapidly between equidistant mobs.
            if (distance < best - 1e-7 ||
                (Math.abs(distance - best) <= 1e-7 &&
                 (nearest == null || living.getId() < nearest.getId()))) {
                nearest = living;
                best = distance;
            }
        }
        return nearest;
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            resetTarget();
            return;
        }

        String filter = nameContains.get().trim().toLowerCase(Locale.ROOT);
        double limitSquared = range.get() * range.get();
        if (!Double.isFinite(limitSquared)) { resetTarget(); return; }

        // At least every 30 real seconds invalidate the cached target reference.
        // Selection already scans every tick, including when NO target is found.
        // Keep motion smoothing for a reacquired identical entity to avoid aim jumps.
        LivingEntity previousTarget = lockedTarget;
        long now = System.nanoTime();
        if (nextSafetyRescan == 0L || now - nextSafetyRescan >= 0L) {
            lockedTarget = null;
            nextSafetyRescan = now + SAFETY_RESCAN_NANOS;
        }

        LivingEntity target = selectClosestTarget(filter, limitSquared);
        if (target != previousTarget) {
            smoothedMotion = Vec3.ZERO;
            fireTimer = 0;
        }
        lockedTarget = target;

        // Always update target selection even with auto-aim disabled.
        if (target == null || !aim.get()) return;

        Vec3 raw = target.getDeltaMovement();
        if (Double.isFinite(raw.x) && Double.isFinite(raw.y) && Double.isFinite(raw.z)
            && raw.lengthSqr() < 100) {
            smoothedMotion = smoothedMotion.scale(0.75).add(raw.scale(0.25));
        }

        Vec3 origin = mc.player.getEyePosition();
        double relativeY = target.getBbHeight() * aimHeight.get() + extraHeight.get();
        if (!Double.isFinite(relativeY)) return;

        Vec3 targetPos = target.position().add(0, relativeY, 0);
        Vec3 motion = lead.get() ? smoothedMotion : Vec3.ZERO;
        Vec3 direction = Ballistics.solve(origin, targetPos, motion,
            speed.get(), gravity.get(), drag.get());
        if (direction == null) return;

        double horizontal = Math.hypot(direction.x, direction.z);
        if (horizontal < 1e-8) return;

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontal));
        if (!Float.isFinite(desiredYaw) || !Float.isFinite(desiredPitch)) return;

        float step = (float) Math.min(rotationSpeed.get(), 180.0);
        if (!Float.isFinite(step) || step <= 0) return;
        float yawDelta = (float) Math.IEEEremainder(desiredYaw - mc.player.getYRot(), 360.0);
        float pitchDelta = desiredPitch - mc.player.getXRot();

        mc.player.setYRot(mc.player.getYRot() + clamp(yawDelta, step));
        mc.player.setXRot(Math.max(-90, Math.min(90,
            mc.player.getXRot() + clamp(pitchDelta, step))));

        // Optionally run while screens are open, as requested for inventory use.
        // Meteor's Utils.leftClick invokes the in-game attack action directly.
        if (!autoFire.get() || (mc.screen != null && !fireInGuis.get())) return;
        fireTimer++;
        if (fireTimer < fireInterval.get()) return;

        double yawError = Math.abs(Math.IEEEremainder(desiredYaw - mc.player.getYRot(), 360.0));
        double pitchError = Math.abs(desiredPitch - mc.player.getXRot());
        if (yawError <= fireTolerance.get() && pitchError <= fireTolerance.get()) {
            Utils.leftClick();
            lastAutoFireNanos = System.nanoTime();
            fireTimer = 0;
        }
    }

    private static float clamp(float delta, float cap) {
        return Math.max(-cap, Math.min(cap, delta));
    }

    @Override public String getInfoString() {
        return lockedTarget == null ? null : lockedTarget.getName().getString();
    }
}
