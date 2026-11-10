package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
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

/** ProjectileAura 0.2: stable trajectory prediction and configurable target selection. */
public class ProjectileAuraModule extends Module {
    private final SettingGroup physics = settings.getDefaultGroup();
    private final SettingGroup targeting = settings.createGroup("Targeting");
    private final SettingGroup rotation = settings.createGroup("Rotation");

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

    private final Setting<Boolean> sticky = targeting.add(new BoolSetting.Builder()
        .name("sticky-target").description("Retain current valid target instead of jumping between mobs.")
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

    private LivingEntity lockedTarget;
    private Vec3 smoothedMotion = Vec3.ZERO;

    public ProjectileAuraModule() {
        super(ProjectileAuraAddon.CATEGORY, "projectile-aura",
            "Stable aiming for custom arrow weapons, with adjustable projectile physics.");
    }

    @Override public void onActivate() { resetTarget(); }
    @Override public void onDeactivate() { resetTarget(); }

    private void resetTarget() {
        lockedTarget = null;
        smoothedMotion = Vec3.ZERO;
    }

    private boolean validTarget(LivingEntity entity, String filter, double limitSquared) {
        if (entity == mc.player || !entity.isAlive() || !entities.get().contains(entity.getType())) return false;
        if (mc.player.distanceToSqr(entity) > limitSquared) return false;
        if (!filter.isEmpty() && !entity.getName().getString().toLowerCase(Locale.ROOT).contains(filter)) return false;
        if (entity instanceof Player player && (player.isCreative() || !Friends.get().shouldAttack(player))) return false;
        return !requireSight.get() || PlayerUtils.canSeeEntity(entity);
    }

    private LivingEntity selectTarget(String filter, double limitSquared) {
        if (sticky.get() && lockedTarget != null && validTarget(lockedTarget, filter, limitSquared)) return lockedTarget;
        LivingEntity nearest = null;
        double best = limitSquared;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !validTarget(living, filter, limitSquared)) continue;
            double d = mc.player.distanceToSqr(living);
            if (d < best) { best = d; nearest = living; }
        }
        return nearest;
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) { resetTarget(); return; }
        if (!aim.get()) return;

        String filter = nameContains.get().trim().toLowerCase(Locale.ROOT);
        double limitSquared = range.get() * range.get();
        if (!Double.isFinite(limitSquared)) return;

        LivingEntity target = selectTarget(filter, limitSquared);
        if (target != lockedTarget) {
            lockedTarget = target;
            smoothedMotion = Vec3.ZERO;
        }
        if (target == null) return;

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
    }

    private static float clamp(float delta, float cap) {
        return Math.max(-cap, Math.min(cap, delta));
    }

    @Override public String getInfoString() {
        return lockedTarget == null ? null : lockedTarget.getName().getString();
    }
}
