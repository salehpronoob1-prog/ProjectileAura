package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

/** Right-click the first golden sword in an open container, once per appearance. */
public class PetBattleModule extends Module {
    private AbstractContainerScreen<?> currentScreen;
    private boolean clicked;

    public PetBattleModule() {
        super(ProjectileAuraAddon.CATEGORY, "pet-battle",
            "Automatically right-click a golden sword in an open GUI.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null
            || mc.player.isDeadOrDying()
            || !(mc.screen instanceof AbstractContainerScreen<?> screen)
            || mc.player.containerMenu != screen.getMenu()) {
            reset();
            return;
        }

        if (currentScreen != screen) {
            currentScreen = screen;
            clicked = false;
        }

        AbstractContainerMenu menu = screen.getMenu();
        int target = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == mc.player.getInventory()) continue;
            if (slot.getItem().is(Items.GOLDEN_SWORD)) {
                target = i;
                break;
            }
        }

        if (target == -1) {
            clicked = false;
            return;
        }

        // Don't interfere with an item already held on the mouse cursor.
        if (clicked || !menu.getCarried().isEmpty()) return;

        clicked = true;
        // PICKUP with mouse button 1 is a normal right-click on a container slot.
        mc.gameMode.handleInventoryMouseClick(menu.containerId, target, 1,
            ClickType.PICKUP, mc.player);
    }

    private void reset() {
        currentScreen = null;
        clicked = false;
    }
}
