package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Automates pet-battle player interaction and the golden-sword GUI action. */
public class PetBattleModule extends Module {
    private final SettingGroup general = settings.getDefaultGroup();

    private final Setting<Boolean> autoInteract = general.add(new BoolSetting.Builder()
        .name("auto-interact-players")
        .description("Aim at and right-click the nearest visible player within normal interaction reach.")
        .defaultValue(true).build());

    private final Setting<Integer> intervalSeconds = general.add(new IntSetting.Builder()
        .name("interaction-interval-seconds")
        .description("Minimum real-time seconds between player right-clicks.")
        .defaultValue(5).min(1).sliderRange(1, 60).visible(autoInteract::get).build());

    private AbstractContainerScreen<?> currentScreen;
    private boolean clicked;
    private long lastInteractionAt;
    private boolean hasInteracted;

    public PetBattleModule() {
        super(ProjectileAuraAddon.CATEGORY, "pet-battle",
            "Right-click nearby players and shift-right-click golden swords in GUIs.");
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
            || mc.player.isDeadOrDying()) {
            reset();
            return;
        }

        // Screens and world interactions are mutually exclusive.
        if (mc.screen != null) {
            if (mc.screen instanceof AbstractContainerScreen<?> screen
                && mc.player.containerMenu == screen.getMenu()) {
                handleGui(screen);
            } else {
                resetGui();
            }
            return;
        }

        resetGui();
        if (autoInteract.get() && !mc.player.isSpectator()) handlePlayers();
    }

    private void handlePlayers() {
        long now = System.nanoTime();
        if (hasInteracted && now - lastInteractionAt < intervalSeconds.get() * 1_000_000_000L) return;

        Player nearest = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player player) || player == mc.player
                || !player.isAlive() || player.isSpectator()
                || !mc.player.isWithinEntityInteractionRange(player, 0)
                || !PlayerUtils.canSeeEntity(player)) continue;

            double distance = mc.player.distanceToSqr(player);
            if (distance < bestDistance || (distance == bestDistance
                && nearest != null && player.getId() < nearest.getId())) {
                bestDistance = distance;
                nearest = player;
            }
        }
        if (nearest == null) return;

        // Aim at the player's torso before interacting with that specific entity.
        Vec3 direction = nearest.position().add(0, nearest.getBbHeight() * 0.65, 0)
            .subtract(mc.player.getEyePosition());
        double horizontal = Math.hypot(direction.x, direction.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontal));
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;

        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);

        // Normal main-hand entity interaction; never synthesize a global mouse
        // click or press the sneak key. GUI clicks use a different action below.
        hasInteracted = true;
        lastInteractionAt = now;
        mc.gameMode.interact(mc.player, nearest, InteractionHand.MAIN_HAND);
    }

    private void handleGui(AbstractContainerScreen<?> screen) {
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
        // QUICK_MOVE with mouse button 1 represents shift-right-click on a GUI slot.
        mc.gameMode.handleInventoryMouseClick(menu.containerId, target, 1,
            ClickType.QUICK_MOVE, mc.player);
    }

    private void resetGui() {
        currentScreen = null;
        clicked = false;
    }

    private void reset() {
        resetGui();
        hasInteracted = false;
        lastInteractionAt = 0;
    }
}
