package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashSet;
import java.util.Set;

/**
 * Read-only GUI inventory dumper for browsing server catalogs.
 *
 * The player opens the GUI and changes pages manually. Each distinct stable
 * page is printed once to local Meteor chat; no commands or clicks are sent.
 */
public class GuiDumperModule extends Module {
    private final SettingGroup general = settings.getDefaultGroup();

    private final Setting<Boolean> includePlayerInventory = general.add(new BoolSetting.Builder()
        .name("include-player-inventory")
        .description("Include your own inventory slots at the bottom of container screens.")
        .defaultValue(false).build());

    private final Setting<Boolean> showComponents = general.add(new BoolSetting.Builder()
        .name("show-raw-components")
        .description("Also print the raw item components (can produce very long chat messages).")
        .defaultValue(false).build());

    private final Set<String> dumpedPages = new HashSet<>();
    private AbstractContainerScreen<?> currentScreen;
    private String candidateSnapshot;
    private int stableObservations;
    private int tickCounter;
    private int pageNumber;

    public GuiDumperModule() {
        super(ProjectileAuraAddon.CATEGORY, "gui-dumper",
            "Read every item name and lore in an open container GUI; dump each page to local chat.");
    }

    @Override
    public void onActivate() {
        resetSession();
    }

    @Override
    public void onDeactivate() {
        resetSession();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null
            || !(mc.screen instanceof AbstractContainerScreen<?> screen)
            || mc.player.containerMenu != screen.getMenu()) {
            resetSession();
            return;
        }

        // Reopening the same inventory starts a fresh capture session.
        if (screen != currentScreen) {
            resetSession();
            currentScreen = screen;
        }

        // Three matching observations spaced five ticks apart avoid capturing
        // partially received page updates.
        if (++tickCounter % 5 != 0) return;

        String snapshot = buildSnapshot(screen);
        if (!snapshot.equals(candidateSnapshot)) {
            candidateSnapshot = snapshot;
            stableObservations = 1;
            return;
        }

        if (++stableObservations < 3 || !dumpedPages.add(snapshot)) return;
        dumpScreen(screen);
    }

    private String buildSnapshot(AbstractContainerScreen<?> screen) {
        StringBuilder signature = new StringBuilder();
        signature.append(screen.getTitle().getString()).append('|');
        AbstractContainerMenu menu = screen.getMenu();
        signature.append(menu.slots.size()).append('|');

        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (skipSlot(slot)) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            signature.append(i).append(':')
                .append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(':')
                .append(stack.getCount()).append(':')
                .append(stack.getHoverName().getString()).append(':')
                .append(stack.getComponents()).append(';');
        }
        return signature.toString();
    }

    private boolean skipSlot(Slot slot) {
        return !includePlayerInventory.get()
            && slot.container == mc.player.getInventory();
    }

    private void dumpScreen(AbstractContainerScreen<?> screen) {
        pageNumber++;
        AbstractContainerMenu menu = screen.getMenu();
        String title = screen.getTitle().getString();
        info("%s", "===== GUI DUMP PAGE " + pageNumber + " | " + title + " =====");

        int count = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (skipSlot(slot)) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            count++;

            StringBuilder entry = new StringBuilder();
            entry.append("Slot ").append(i)
                .append(" | ").append(stack.getHoverName().getString())
                .append(" | ").append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .append(" x").append(stack.getCount());

            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                entry.append(" | Lore: ");
                boolean first = true;
                for (Component line : lore.lines()) {
                    if (!first) entry.append(" / ");
                    entry.append(line.getString().replace('\n', ' ').replace('\r', ' '));
                    first = false;
                }
            }
            info("%s", entry.toString());

            if (showComponents.get()) {
                info("%s", "  Components [" + i + "]: " + stack.getComponents());
            }
        }

        info("%s", "===== END PAGE " + pageNumber + " (" + count + " items) =====");
    }

    private void resetSession() {
        dumpedPages.clear();
        currentScreen = null;
        candidateSnapshot = null;
        stableObservations = 0;
        tickCounter = 0;
        pageNumber = 0;
    }

    @Override
    public String getInfoString() {
        return pageNumber > 0 ? pageNumber + " pages" : "Waiting for GUI";
    }
}
