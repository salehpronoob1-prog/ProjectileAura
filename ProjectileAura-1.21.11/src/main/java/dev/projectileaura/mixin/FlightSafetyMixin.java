package dev.projectileaura.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skip Flight ticks during a disconnect or a world transition. */
@Mixin(targets = "meteordevelopment.meteorclient.systems.modules.movement.Flight", remap = false)
public abstract class FlightSafetyMixin {
    @Inject(method = {"onPreTick", "onPostTick"}, at = @At("HEAD"),
        cancellable = true, remap = false, require = 2)
    private void projectileaura$skipInvalidFlightTick(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) ci.cancel();
    }
}
