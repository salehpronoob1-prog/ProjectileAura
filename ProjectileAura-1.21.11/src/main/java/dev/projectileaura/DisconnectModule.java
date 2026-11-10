package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Locale;

/**
 * Reconnect watchdog for a stalled /home loop.
 * It runs in menus, but ONLY acts when explicitly configured to the currently joined server.
 */
public class DisconnectModule extends Module {
    private static final long SECOND = 1_000_000_000L;

    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup recovery = settings.createGroup("Recovery");

    private final Setting<String> serverAddress = general.add(new StringSetting.Builder()
        .name("server-address")
        .description("The exact original server address (with optional port). Blank disables reconnection.")
        .defaultValue("").build());

    private final Setting<Integer> failedHomeAttempts = recovery.add(new IntSetting.Builder()
        .name("failed-home-attempts")
        .description("Number of /home attempts that must fail before reconnecting.")
        .defaultValue(3).min(2).sliderRange(2, 10).build());

    private final Setting<Double> homeResultDelay = recovery.add(new DoubleSetting.Builder()
        .name("home-result-seconds")
        .description("Wait after the latest /home before treating attempts as failed.")
        .defaultValue(10).min(1).sliderRange(3, 30).onSliderRelease().build());

    private final Setting<Double> connectionTimeout = recovery.add(new DoubleSetting.Builder()
        .name("connect-timeout-seconds")
        .description("Time allowed to rejoin the same server.")
        .defaultValue(10).min(1).sliderRange(5, 30).onSliderRelease().build());

    private final Setting<Double> retryDelay = recovery.add(new DoubleSetting.Builder()
        .name("retry-delay-seconds")
        .description("Wait after failed/slow connection attempts before trying again.")
        .defaultValue(30).min(1).sliderRange(5, 90).onSliderRelease().build());

    private final Setting<Integer> maxRetries = recovery.add(new IntSetting.Builder()
        .name("max-retries")
        .description("Maximum reconnection attempts in one recovery cycle (0 = unlimited).")
        .defaultValue(10).min(0).sliderRange(0, 30).build());

    private enum State { IDLE, START_DELAY, CONNECTING, RETRY_DELAY, ABORTED }
    private State state = State.IDLE;

    private String originalServer;
    private long nextActionAt;
    private long connectStartedAt;
    private int attempts;
    private String reason;

    public DisconnectModule() {
        super(ProjectileAuraAddon.CATEGORY, "disconnect",
            "Recover from a stuck home-return loop by safely reconnecting to the same configured server.");
        // Ticks must keep arriving while Minecraft is in the title/disconnected screens.
        runInMainMenu = true;
    }

    @Override public void onActivate() {
        reset();
    }

    @Override public void onDeactivate() {
        reset();
    }

    private void reset() {
        state = State.IDLE;
        attempts = 0;
        originalServer = null;
        nextActionAt = 0;
        connectStartedAt = 0;
        reason = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.nanoTime();

        if (state == State.IDLE) {
            checkStuckHomeLoop(now);
            return;
        }

        // Clearing the setting disarms all pending reconnect activity.
        if (serverAddress.get().trim().isEmpty()) {
            state = State.ABORTED;
            reason = "Missing server";
            return;
        }

        if (state == State.ABORTED) return;

        String configured = normalize(serverAddress.get());
        if (configured == null || !configured.equals(originalServer)) {
            state = State.ABORTED;
            reason = "Server changed";
            return;
        }

        // Never continue on an unexpected server, even after joining.
        if (mc.level != null && mc.player != null) {
            String joined = currentServer();
            if (joined == null || !joined.equals(originalServer)) {
                state = State.ABORTED;
                reason = "Wrong server";
                return;
            }

            if (state == State.CONNECTING) {
                state = State.IDLE;
                attempts = 0;
                SpawnReturnModule home = Modules.get().get(SpawnReturnModule.class);
                if (home != null) home.resetHomeAttempts();
                info("Reconnected to (highlight)%s(default).", originalServer);
            }
            return;
        }

        if (state == State.START_DELAY || state == State.RETRY_DELAY) {
            if (now - nextActionAt >= 0) startConnection(now);
        } else if (state == State.CONNECTING) {
            if (mc.screen instanceof DisconnectedScreen) {
                scheduleRetry(now, "Connection failed");
            } else if (now - connectStartedAt >= seconds(connectionTimeout.get())) {
                // Cancel the current ConnectScreen by returning to the title screen.
                // A late connection is invalidated by the screen's cancellation handler.
                mc.disconnect(new TitleScreen(), false);
                scheduleRetry(now, "Connection timeout");
            }
        }
    }

    private void checkStuckHomeLoop(long now) {
        if (mc.player == null || mc.level == null || mc.getCurrentServer() == null) return;
        if (serverAddress.get().trim().isEmpty()) return;

        SpawnReturnModule home = Modules.get().get(SpawnReturnModule.class);
        if (home == null || !home.isActive() ||
            !home.isCheckpointConfigured() || !home.isOutsideCheckpoint() ||
            home.hasNearbyEnemiesForReturn() ||
            home.getUnsuccessfulHomeAttempts() < failedHomeAttempts.get()) return;

        long lastHome = home.getLastCommandAttemptNanos();
        if (lastHome == 0 || now - lastHome < seconds(homeResultDelay.get())) return;

        String configured = normalize(serverAddress.get());
        String connected = currentServer();
        if (configured == null || connected == null || !configured.equals(connected)) return;

        originalServer = connected;
        attempts = 0;
        reason = null;
        state = State.START_DELAY;
        nextActionAt = now + SECOND;

        // Disconnect only from the matched original server.
        mc.disconnect(new TitleScreen(), false);
        info("Stuck /home detected. Reconnecting to (highlight)%s(default).", originalServer);
    }

    private void startConnection(long now) {
        if (maxRetries.get() != 0 && attempts >= maxRetries.get()) {
            state = State.ABORTED;
            reason = "Retry limit";
            return;
        }
        if (mc.player != null || mc.level != null) {
            state = State.ABORTED;
            reason = "Unexpected session";
            return;
        }

        String addressText = serverAddress.get().trim();
        if (addressText.isEmpty()) {
            state = State.ABORTED;
            reason = "Missing server";
            return;
        }

        ServerAddress address;
        try {
            if (!ServerAddress.isValidAddress(addressText)) {
                state = State.ABORTED;
                reason = "Invalid address";
                return;
            }
            address = ServerAddress.parseString(addressText);
        } catch (RuntimeException e) {
            state = State.ABORTED;
            reason = "Invalid address";
            return;
        }

        // Only explicit, normalized, exact endpoint matches are permitted.
        if (!normalize(addressText).equals(originalServer)) {
            state = State.ABORTED;
            reason = "Server mismatch";
            return;
        }

        attempts++;
        connectStartedAt = now;
        state = State.CONNECTING;
        ServerData data = new ServerData("ProjectileAura Recovery", addressText, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(), mc, address, data, false, null);
    }

    private void scheduleRetry(long now, String why) {
        if (mc.screen instanceof ConnectScreen) mc.disconnect(new TitleScreen(), false);
        nextActionAt = now + seconds(retryDelay.get());
        state = State.RETRY_DELAY;
        reason = why;
    }

    private String currentServer() {
        ServerData info = mc.getCurrentServer();
        if (info == null || info.isRealm()) return null;
        return normalize(info.ip);
    }

    private static String normalize(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty() || raw.contains("/") || raw.contains(" ") || raw.contains("\\") ||
            raw.contains("\n") || raw.contains("\r")) return null;
        try {
            if (!ServerAddress.isValidAddress(raw)) return null;
            ServerAddress address = ServerAddress.parseString(raw);
            return address.getHost().toLowerCase(Locale.ROOT) + ":" + address.getPort();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long seconds(double value) {
        if (!Double.isFinite(value)) return 30 * SECOND;
        return (long) (Math.max(0, Math.min(value, 3600)) * SECOND);
    }

    @Override public String getInfoString() {
        return switch (state) {
            case IDLE -> "Watching";
            case START_DELAY -> "Disconnecting";
            case CONNECTING -> "Connecting " + attempts;
            case RETRY_DELAY -> "Retry " + attempts;
            case ABORTED -> reason == null ? "Stopped" : reason;
        };
    }
}
