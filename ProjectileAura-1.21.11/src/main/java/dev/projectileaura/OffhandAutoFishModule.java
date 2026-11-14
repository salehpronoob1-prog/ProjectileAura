package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * Independent Meteor module for fishing with the offhand rod.
 * Does not change the selected main-hand item or player rotation.
 */
public class OffhandAutoFishModule extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Integer> recastDelay = general.add(new IntSetting.Builder()
        .name("recast-delay-ticks")
        .description("Ticks to wait after reeling in before casting again.")
        .defaultValue(12).min(2).sliderRange(2, 40).build());

    private final Setting<Double> biteThreshold = general.add(new DoubleSetting.Builder()
        .name("bite-threshold")
        .description("Downward bobber velocity indicating a bite; lower values are more sensitive.")
        .defaultValue(0.08).min(0.02).sliderRange(0.02, 0.25).build());

    private final OffhandAutoFishing fishing = new OffhandAutoFishing();

    public OffhandAutoFishModule() {
        super(ProjectileAuraAddon.CATEGORY, "offhand-auto-fish",
            "Fish using a rod in your offhand while keeping your bow in the main hand.");
    }

    @Override public void onActivate() { fishing.reset(); }
    @Override public void onDeactivate() { fishing.reset(); }

    @EventHandler private void onTick(TickEvent.Post event) {
        fishing.tick(mc, true, recastDelay.get(), biteThreshold.get());
    }
}
