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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

/**
 * Read-only GUI inventory dumper for browsing server catalogs.
 *
 * A capture session lasts from enabling this module until disabling it, even
 * across new GUI screen instances and inventory page transitions.
 * Every unique, stable page is appended to the accumulated text, saved on
 * disk, and copied to the clipboard. No commands or clicks are sent.
 */
public class GuiDumperModule extends Module {
    private final SettingGroup general = settings.getDefaultGroup();

    private final Setting<Boolean> includePlayerInventory = general.add(new BoolSetting.Builder()
        .name("include-player-inventory")
        .description("Include your own inventory slots at the bottom of container screens.")
        .defaultValue(false).build());

    private final Setting<Boolean> showComponents = general.add(new BoolSetting.Builder()
        .name("show-raw-components")
        .description("Also dump raw item components (may produce a very long dump).")
        .defaultValue(false).build());

    private final Setting<Boolean> autoCopy = general.add(new BoolSetting.Builder()
        .name("auto-copy-all-pages")
        .description("Copy the entire accumulated GUI dump to the clipboard after every new page.")
        .defaultValue(true).build());

    private final Setting<Boolean> saveToFile = general.add(new BoolSetting.Builder()
        .name("save-to-text-file")
        .description("Keep a full text backup in the Minecraft gui-dumps folder.")
        .defaultValue(true).build());

    private final Set<String> dumpedPages = new HashSet<>();
    private final StringBuilder allPages = new StringBuilder();
    private AbstractContainerScreen<?> currentScreen;
    private String candidateSnapshot;
    private int stableObservations;
    private int tickCounter;
    private int pageNumber;
    private Path outputFile;

    public GuiDumperModule() {
        super(ProjectileAuraAddon.CATEGORY, "gui-dumper",
            "Read all item lore in an open GUI, automatically copying and saving every scanned page.");
    }

    @Override
    public void onActivate() {
        resetSession();
    }

    @Override
    public void onDeactivate() {
        // Text already copied to the clipboard and saved on disk remains intact.
        resetSession();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null
            || !(mc.screen instanceof AbstractContainerScreen<?> screen)
            || mc.player.containerMenu != screen.getMenu()) {
            clearCurrentScreen();
            return;
        }

        // Page navigation sometimes replaces the screen with another instance.
        // Keep all previous pages, only reset the stability check.
        if (screen != currentScreen) {
            clearCurrentScreen();
            currentScreen = screen;
        }

        // Three matching samples spaced five ticks apart to avoid incomplete updates.
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

        String header = "===== GUI DUMP PAGE " + pageNumber + " | " + title + " =====";
        StringBuilder page = new StringBuilder(header).append('\n');
        info("%s", header);

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

            String itemText = entry.toString();
            page.append(itemText).append('\n');
            info("%s", itemText);

            if (showComponents.get()) {
                String componentText = "  Components [" + i + "]: " + stack.getComponents();
                page.append(componentText).append('\n');
                info("%s", componentText);
            }
        }

        String footer = "===== END PAGE " + pageNumber + " (" + count + " items) =====";
        page.append(footer).append("\n\n");
        allPages.append(page);
        info("%s", footer);

        // Persist before copying so a system clipboard failure cannot lose data.
        if (saveToFile.get()) saveAccumulatedText();
        if (autoCopy.get()) {
            try {
                mc.keyboardHandler.setClipboard(allPages.toString());
                info("Copied all %d page(s) (%d characters) to clipboard. Paste with Ctrl+V.", pageNumber, allPages.length());
            } catch (Exception exception) {
                error("Couldn't copy GUI dump: %s", exception.getMessage());
            }
        }
    }

    private void saveAccumulatedText() {
        try {
            if (outputFile == null) {
                Path directory = mc.gameDirectory.toPath().resolve("gui-dumps");
                Files.createDirectories(directory);
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
                outputFile = directory.resolve("gui-dump-" + stamp + "-" + System.currentTimeMillis() + ".txt");
            }
            Files.writeString(outputFile, allPages.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            error("Couldn't save GUI dump: %s", exception.getMessage());
        }
    }

    private void clearCurrentScreen() {
        currentScreen = null;
        candidateSnapshot = null;
        stableObservations = 0;
        tickCounter = 0;
    }

    private void resetSession() {
        dumpedPages.clear();
        allPages.setLength(0);
        clearCurrentScreen();
        pageNumber = 0;
        outputFile = null;
    }

    @Override
    public String getInfoString() {
        return pageNumber > 0 ? pageNumber + " pages" : "Waiting for GUI";
    }
}
