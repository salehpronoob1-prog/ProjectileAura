package dev.projectileaura;

import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.entity.LivingEntity;

import java.util.Locale;

/**
 * Returns to a configured home after death or when outside a checkpoint.
 * Teleport attempts wait for recent shooting and nearby selected enemies.
 */
public class SpawnReturnModule extends Module {
    private static final long NANOSECONDS_PER_SECOND = 1_000_000_000L;

    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup location = settings.createGroup("Checkpoint");
    private final SettingGroup safety = settings.createGroup("Safety");

    private final Setting<String> command = general.add(new StringSetting.Builder()
        .name("return-command")
        .description("Command to send when safe; /home master is only an example.")
        .defaultValue("/home master").build());

    private final Setting<Boolean> afterDeath = general.add(new BoolSetting.Builder()
        .name("return-after-death")
        .description("Attempt return after a death/respawn.")
        .defaultValue(true).build());

    private final Setting<Boolean> autoRespawn = general.add(new BoolSetting.Builder()
        .name("auto-respawn")
        .description("Automatically respawn from the death screen.")
        .defaultValue(true).visible(afterDeath::get).build());

    private final Setting<Boolean> checkPosition = location.add(new BoolSetting.Builder()
        .name("check-coordinates")
        .description("Return if outside your configured checkpoint area. Enable only after entering the right coordinates.")
        .defaultValue(false).build());

    private final Setting<Double> homeX = location.add(new DoubleSetting.Builder()
        .name("home-x").description("Checkpoint X coordinate.")
        .defaultValue(0).sliderRange(-1000, 1000).decimalPlaces(2).onSliderRelease().build());

    private final Setting<Double> homeY = location.add(new DoubleSetting.Builder()
        .name("home-y").description("Checkpoint Y coordinate.")
        .defaultValue(64).sliderRange(-64, 320).decimalPlaces(2).onSliderRelease().build());

    private final Setting<Double> homeZ = location.add(new DoubleSetting.Builder()
        .name("home-z").description("Checkpoint Z coordinate.")
        .defaultValue(0).sliderRange(-1000, 1000).decimalPlaces(2).onSliderRelease().build());

    private final Setting<Double> tolerance = location.add(new DoubleSetting.Builder()
        .name("position-tolerance")
        .description("Horizontal radius (blocks) treated as being at your home.")
        .defaultValue(6).min(0.5).sliderRange(1, 30).decimalPlaces(2).onSliderRelease().build());

    private final Setting<Double> yTolerance = location.add(new DoubleSetting.Builder()
        .name("vertical-tolerance")
        .description("Allowed height difference from home Y.")
        .defaultValue(8).min(0.5).sliderRange(1, 30).decimalPlaces(2).onSliderRelease().build());

    private final Setting<Double> nearbyRange = safety.add(new DoubleSetting.Builder()
        .name("enemy-check-radius")
        .description("Do not teleport while selected enemy types are nearby.")
        .defaultValue(28).min(0.5).sliderRange(4, 80).decimalPlaces(2).onSliderRelease().build());

    private final Setting<Boolean> selectedOnly = safety.add(new BoolSetting.Builder()
        .name("projectile-aura-enemies-only")
        .description("Use ProjectileAura's selected entity types and name filter; otherwise count all living non-player entities.")
        .defaultValue(true).build());

    private final Setting<Double> cooldown = safety.add(new DoubleSetting.Builder()
        .name("combat-cooldown-seconds")
        .description("Wait this long after ProjectileAura's last shot before trying to teleport.")
        .defaultValue(20).min(0).sliderRange(0, 90).decimalPlaces(2).onSliderRelease().build());

    private final Setting<Double> quietSeconds = safety.add(new DoubleSetting.Builder()
        .name("enemy-clear-seconds")
        .description("How long the nearby area must remain free of enemies.")
        .defaultValue(3).min(0).sliderRange(0, 20).decimalPlaces(2).onSliderRelease().build());

    private final Setting<Double> retrySeconds = safety.add(new DoubleSetting.Builder()
        .name("retry-seconds")
        .description("Minimum time between command attempts if the position is still incorrect.")
        .defaultValue(15).min(1).sliderRange(2, 60).decimalPlaces(2).onSliderRelease().build());

    private boolean pendingDeathReturn;
    private boolean deathObserved;
    private long noEnemiesSince;
    private long nextAttemptAt;
    // Consecutive commands sent while still outside the configured checkpoint.
    private int unsuccessfulHomeAttempts;
    private long lastCommandAttemptNanos;

    public boolean isCheckpointConfigured() {
        return checkPosition.get();
    }

    public boolean isOutsideCheckpoint() {
        return mc.player != null && mc.level != null && checkPosition.get() && !atCheckpoint();
    }

    public int getUnsuccessfulHomeAttempts() {
        return isActive() && isOutsideCheckpoint() ? unsuccessfulHomeAttempts : 0;
    }

    public long getLastCommandAttemptNanos() {
        return lastCommandAttemptNanos;
    }

    public void resetHomeAttempts() {
        unsuccessfulHomeAttempts = 0;
        lastCommandAttemptNanos = 0;
    }

    public SpawnReturnModule() {
        super(ProjectileAuraAddon.CATEGORY, "spawn-return",
            "Return home after death or leaving a checkpoint, when enemies and combat cooldown permit.");
    }

    @Override public void onActivate() {
        pendingDeathReturn = false;
        deathObserved = false;
        noEnemiesSince = 0;
        nextAttemptAt = 0;
        resetHomeAttempts();
    }

    @Override public void onDeactivate() {
        pendingDeathReturn = false;
        noEnemiesSince = 0;
        resetHomeAttempts();
    }

    @EventHandler private void onDeathScreen(OpenScreenEvent event) {
        if (!(event.screen instanceof DeathScreen) || !afterDeath.get()) return;
        pendingDeathReturn = true;
        deathObserved = true;
        if (autoRespawn.get() && mc.player != null) {
            mc.player.respawn();
            event.cancel();
        }
    }

    @EventHandler private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) {
            noEnemiesSince = 0;
            return;
        }

        if (mc.player.isDeadOrDying()) {
            if (afterDeath.get()) {
                pendingDeathReturn = true;
                deathObserved = true;
            }
            noEnemiesSince = 0;
            return;
        }

        // Preserve pending death request until at least one safe command attempt.
        if (deathObserved) deathObserved = false;

        boolean outside = checkPosition.get() && !atCheckpoint();
        if (!outside && checkPosition.get()) resetHomeAttempts();
        if (!pendingDeathReturn && !outside) return;

        // Don't send commands while a death screen is still visible.
        if (mc.screen instanceof DeathScreen) return;

        long now = System.nanoTime();
        if (hasNearbyEnemies()) {
            noEnemiesSince = 0;
            return;
        }

        if (noEnemiesSince == 0) noEnemiesSince = now;
        if (secondsSince(now, noEnemiesSince) < quietSeconds.get()) return;

        long lastShot = ProjectileAuraModule.getLastAutoFireNanos();
        if (lastShot != 0 && secondsSince(now, lastShot) < cooldown.get()) return;
        if (now - nextAttemptAt < 0) return;

        String cmd = command.get().trim();
        if (cmd.isEmpty() || cmd.equals("/")) return;
        cmd = cmd.startsWith("/") ? cmd : "/" + cmd;

        // Send only a single command, never a batch or multi-line input.
        if (cmd.contains("\n") || cmd.contains("\r")) return;
        ChatUtils.sendPlayerMsg(cmd, false);
        if (outside && unsuccessfulHomeAttempts < 1_000_000) unsuccessfulHomeAttempts++;
        lastCommandAttemptNanos = now;

        pendingDeathReturn = false;
        nextAttemptAt = now + (long) (retrySeconds.get() * NANOSECONDS_PER_SECOND);
        noEnemiesSince = 0;
    }

    private boolean atCheckpoint() {
        double dx = mc.player.getX() - homeX.get();
        double dz = mc.player.getZ() - homeZ.get();
        double dy = Math.abs(mc.player.getY() - homeY.get());
        return dx * dx + dz * dz <= tolerance.get() * tolerance.get() && dy <= yTolerance.get();
    }

    public boolean hasNearbyEnemiesForReturn() {
        return mc.player == null || mc.level == null || hasNearbyEnemies();
    }

    private boolean hasNearbyEnemies() {
        double r2 = nearbyRange.get() * nearbyRange.get();
        ProjectileAuraModule aura = Modules.get().get(ProjectileAuraModule.class);
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == mc.player || !living.isAlive()) continue;
            if (mc.player.distanceToSqr(living) > r2) continue;
            if (selectedOnly.get()) {
                if (aura == null || !aura.matchesSelectedEntity(living)) continue;
            } else if (living instanceof net.minecraft.world.entity.player.Player) continue;
            return true;
        }
        return false;
    }

    private static double secondsSince(long now, long earlier) {
        return Math.max(0, now - earlier) / (double) NANOSECONDS_PER_SECOND;
    }

    @Override public String getInfoString() {
        return pendingDeathReturn ? "After death" :
            (mc.player != null && checkPosition.get() && !atCheckpoint() ? "Outside checkpoint" : "At home");
    }
}
