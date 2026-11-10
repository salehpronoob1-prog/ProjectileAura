package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * Once per interval: /sack -> wait for the Sack Selection Menu -> click Insert Inventory.
 * Does not interact with any other GUIs or inventory slots.
 */
public class AutoSackModule extends Module {
    private static final long SECOND = 1_000_000_000L;
    private static final int INSERT_SLOT_ID = 30;
    private static final String SACK_MENU_TITLE = "Sack Selection Menu";
    private static final String INSERT_NAME = "insert inventory";

    private final SettingGroup general = settings.getDefaultGroup();

    private final Setting<Integer> intervalSeconds = general.add(new IntSetting.Builder()
        .name("interval-seconds")
        .description("How often to run /sack and click the Insert Inventory hopper.")
        .defaultValue(60).min(10).sliderRange(10, 300).build());

    private final Setting<Integer> menuTimeoutSeconds = general.add(new IntSetting.Builder()
        .name("menu-timeout-seconds")
        .description("How long to wait for the Sack Selection Menu after /sack.")
        .defaultValue(8).min(2).sliderRange(2, 20).build());

    private long nextCommandAt;
    private long menuDeadlineAt;
    private boolean waitingForSackMenu;

    public AutoSackModule() {
        super(ProjectileAuraAddon.CATEGORY, "auto-sack",
            "Periodically open /sack and click the Insert Inventory hopper.");
    }

    @Override
    public void onActivate() {
        nextCommandAt = 0; // Run on the next safe game tick, then every 60 seconds.
        menuDeadlineAt = 0;
        waitingForSackMenu = false;
    }

    @Override
    public void onDeactivate() {
        waitingForSackMenu = false;
        menuDeadlineAt = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            waitingForSackMenu = false;
            return;
        }

        long now = System.nanoTime();

        if (waitingForSackMenu) {
            if (now - menuDeadlineAt >= 0) {
                waitingForSackMenu = false;
                return;
            }

            if (mc.screen == null) return;

            if (!(mc.screen instanceof AbstractContainerScreen<?> screen)
                || !SACK_MENU_TITLE.equals(screen.getTitle().getString())) {
                // Another GUI opened instead; never click it.
                waitingForSackMenu = false;
                return;
            }

            AbstractContainerMenu menu = screen.getMenu();
            if (mc.player.containerMenu != menu || menu.slots.size() <= INSERT_SLOT_ID) return;

            Slot slot = menu.slots.get(INSERT_SLOT_ID);
            ItemStack stack = slot.getItem();

            // Check both the actual hopper item and the server's Insert Inventory label.
            // If the server moves or replaces this control, never click blindly.
            if (!stack.is(Items.HOPPER)
                || !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(INSERT_NAME)) {
                return;
            }

            waitingForSackMenu = false; // One click per /sack, never repeat inside the same GUI.
            InvUtils.click().slotId(INSERT_SLOT_ID);
            return;
        }

        // Do not interrupt a manually opened screen or issue commands while dead.
        if (mc.screen != null || mc.player.isDeadOrDying()) return;
        if (nextCommandAt != 0 && now - nextCommandAt < 0) return;

        waitingForSackMenu = true;
        menuDeadlineAt = now + (long) menuTimeoutSeconds.get() * SECOND;
        nextCommandAt = now + (long) intervalSeconds.get() * SECOND;
        ChatUtils.sendPlayerMsg("/sack", false);
    }

    @Override
    public String getInfoString() {
        if (waitingForSackMenu) return "Waiting for menu";
        if (nextCommandAt == 0) return "Ready";
        long remaining = Math.max(0, (nextCommandAt - System.nanoTime() + SECOND - 1) / SECOND);
        return remaining + "s";
    }
}
