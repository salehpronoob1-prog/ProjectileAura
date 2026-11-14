package dev.projectileaura;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

/**
 * Offhand fishing behavior used by ProjectileAura.
 *
 * This is a plain helper, NOT a Meteor module: it never changes the selected
 * hotbar slot, main-hand item, or player rotation. ProjectileAura's attack
 * logic remains free to use the bow in the main hand.
 */
final class OffhandAutoFishing {
    private int recastCooldown;
    private int hookAge;
    private boolean hadHook;

    void reset() {
        recastCooldown = 0;
        hookAge = 0;
        hadHook = false;
    }

    void tick(Minecraft mc, boolean enabled, int recastDelay, double biteThreshold) {
        if (!enabled || mc.player == null || mc.level == null || mc.gameMode == null
            || !mc.player.getOffhandItem().is(Items.FISHING_ROD)) {
            reset();
            return;
        }

        if (recastCooldown > 0) recastCooldown--;

        var hook = mc.player.fishing;
        if (hook == null) {
            hookAge = 0;

            // Wait after the previous hook disappears to avoid double casting.
            if (hadHook) {
                hadHook = false;
                recastCooldown = Math.max(recastCooldown, recastDelay);
            }

            if (recastCooldown == 0 && mc.screen == null) {
                useOffhandRod(mc);
                // Give the client/server time to spawn the bobber.
                recastCooldown = 10;
            }
            return;
        }

        hadHook = true;
        hookAge++;

        // Fishing bites are indicated by a sudden downward motion of the
        // floating bobber. Skip the initial cast and don't click during GUIs.
        if (hookAge >= 20 && recastCooldown == 0 && hook.isInWater()
            && hook.getDeltaMovement().y < -biteThreshold && mc.screen == null) {
            useOffhandRod(mc);
            hookAge = 0;
            recastCooldown = Math.max(2, recastDelay);
        }
    }

    private static void useOffhandRod(Minecraft mc) {
        mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
    }
}
