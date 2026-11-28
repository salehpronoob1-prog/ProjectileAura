package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;

/** Independent client-side flight with a null-safe connection lifecycle. */
public final class SafeFlightModule extends Module {
    public enum Mode { Abilities, Velocity }

    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Mode> mode = general.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Creative-style flight or direct velocity.")
        .defaultValue(Mode.Abilities).build());
    private final Setting<Double> speed = general.add(new DoubleSetting.Builder()
        .name("speed").description("Horizontal flight speed.")
        .defaultValue(0.1).min(0.01).sliderRange(0.01, 1.0).build());
    private final Setting<Double> verticalMultiplier = general.add(new DoubleSetting.Builder()
        .name("vertical-multiplier").description("Vertical speed multiplier in Velocity mode.")
        .defaultValue(1.0).min(0.1).sliderRange(0.1, 3.0)
        .visible(() -> mode.get() == Mode.Velocity).build());

    private LocalPlayer owner;
    private Mode appliedMode;
    private boolean oldMayFly, oldFlying;
    private float oldFlyingSpeed;

    public SafeFlightModule() {
        super(ProjectileAuraAddon.CATEGORY, "safe-flight",
            "Independent flight that remains enabled through reconnects.");
        runInMainMenu = true;
    }

    @Override public void onActivate() {
        forgetPlayer();
    }

    @Override public void onDeactivate() {
        restorePlayer();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            forgetPlayer();
            return;
        }
        if (owner != player) forgetPlayer();
        if (player.isSpectator() || player.isDeadOrDying() || player.isPassenger()) {
            restorePlayer();
            return;
        }

        Mode selected = mode.get();
        if (owner != player || appliedMode != selected) {
            restorePlayer();
            Abilities abilities = player.getAbilities();
            owner = player;
            appliedMode = selected;
            oldMayFly = abilities.mayfly;
            oldFlying = abilities.flying;
            oldFlyingSpeed = abilities.getFlyingSpeed();
        }

        Abilities abilities = player.getAbilities();
        if (selected == Mode.Abilities) {
            abilities.mayfly = true;
            abilities.flying = true;
            abilities.setFlyingSpeed(speed.get().floatValue());
        } else {
            abilities.flying = false;
            double forward = 0, strafe = 0, vertical = 0;
            if (mc.screen == null) {
                forward = (mc.options.keyUp.isDown() ? 1 : 0)
                    - (mc.options.keyDown.isDown() ? 1 : 0);
                strafe = (mc.options.keyLeft.isDown() ? 1 : 0)
                    - (mc.options.keyRight.isDown() ? 1 : 0);
                vertical = (mc.options.keyJump.isDown() ? 1 : 0)
                    - (mc.options.keyShift.isDown() ? 1 : 0);
            }
            double length = Math.hypot(forward, strafe);
            if (length > 1) {
                forward /= length;
                strafe /= length;
            }
            double yaw = Math.toRadians(player.getYRot());
            double amount = speed.get() * 10.0;
            player.setDeltaMovement(
                (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) * amount,
                vertical * amount * verticalMultiplier.get(),
                (forward * Math.cos(yaw) + strafe * Math.sin(yaw)) * amount);
        }
    }

    private void restorePlayer() {
        if (owner != null && owner == mc.player && mc.level != null && !owner.isSpectator()) {
            Abilities abilities = owner.getAbilities();
            if (appliedMode == Mode.Abilities) {
                abilities.mayfly = oldMayFly;
                abilities.setFlyingSpeed(oldFlyingSpeed);
            }
            abilities.flying = oldFlying && abilities.mayfly;
        }
        forgetPlayer();
    }

    private void forgetPlayer() {
        owner = null;
        appliedMode = null;
    }

    @Override public String getInfoString() {
        return mc.player == null || mc.level == null ? "Waiting" : mode.get().name();
    }
}
