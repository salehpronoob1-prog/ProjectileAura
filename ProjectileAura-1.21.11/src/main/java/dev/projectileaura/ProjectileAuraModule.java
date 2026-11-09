package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

/** Prototype: aims using a numerical ballistic solver; firing is deliberately opt-in. */
public class ProjectileAuraModule extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Double> speed = general.add(new DoubleSetting.Builder().name("arrow-speed").description("Initial arrow speed in blocks per tick.").defaultValue(1.5).range(0.1, 5).sliderRange(0.1, 4).build());
    private final Setting<Double> gravity = general.add(new DoubleSetting.Builder().name("gravity").description("Downward acceleration per tick.").defaultValue(0.05).range(0, 0.2).build());
    private final Setting<Double> drag = general.add(new DoubleSetting.Builder().name("drag").description("Velocity retained per tick.").defaultValue(0.99).range(0.8, 1).build());
    private final Setting<Double> range = general.add(new DoubleSetting.Builder().name("target-range").description("Maximum target distance.").defaultValue(24).range(1, 80).build());
    private final Setting<Double> aimHeight = general.add(new DoubleSetting.Builder().name("aim-height").description("Fraction of target height, from feet to head.").defaultValue(0.65).range(0, 1).build());
    private final Setting<Boolean> aim = general.add(new BoolSetting.Builder().name("auto-aim").description("Rotate view toward predicted impact point.").defaultValue(true).build());
    private final Setting<Boolean> lead = general.add(new BoolSetting.Builder().name("lead-moving-targets").description("Predict movement using current entity velocity.").defaultValue(true).build());

    public ProjectileAuraModule() { super(ProjectileAuraAddon.CATEGORY, "projectile-aura", "Aim a custom arrow weapon with adjustable speed and gravity."); }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || !aim.get()) return;
        LivingEntity target = null;
        double nearest = range.get() * range.get();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Monster mob) || !mob.isAlive()) continue;
            double dist = mc.player.distanceToSqr(mob);
            if (dist < nearest) { nearest = dist; target = mob; }
        }
        if (target == null) return;
        Vec3 origin = mc.player.getEyePosition();
        Vec3 targetPos = target.position().add(0, target.getBbHeight() * aimHeight.get(), 0);
        Vec3 movement = lead.get() ? target.getDeltaMovement() : Vec3.ZERO;
        Vec3 direction = Ballistics.solve(origin, targetPos, movement, speed.get(), gravity.get(), drag.get());
        if (direction == null) return;
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontal));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
    }
}
