package dev.projectileaura;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * Independent Meteor module. Owns SmartNuker's settings and lifecycle.
 * The mining implementation lives in SmartNukerFeature.
 */
public class SmartNukerModule extends Module {
    private final SmartNukerFeature feature = new SmartNukerFeature(settings);

    public SmartNukerModule() {
        super(ProjectileAuraAddon.CATEGORY, "smart-nuker",
            "Automatically mines selected blocks using Packet, Legit or Hybrid modes.");
    }

    @Override public void onActivate() { feature.activate(); }
    @Override public void onDeactivate() { feature.deactivate(); }

    @EventHandler private void onTick(TickEvent.Pre event) { feature.tick(); }
    @EventHandler private void onRender(Render3DEvent event) { feature.render(event); }

    @Override public String getInfoString() { return feature.getInfoString(); }
}
