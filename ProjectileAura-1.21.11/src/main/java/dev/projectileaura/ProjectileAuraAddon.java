package dev.projectileaura;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class ProjectileAuraAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("ProjectileAura");

    @Override public void onRegisterCategories() { Modules.registerCategory(CATEGORY); }
    @Override public void onInitialize() {
        Modules.get().add(new ProjectileAuraModule());
        Modules.get().add(new SpawnReturnModule());
        Modules.get().add(new DisconnectModule());
    }
    @Override public String getPackage() { return "dev.projectileaura"; }
}
