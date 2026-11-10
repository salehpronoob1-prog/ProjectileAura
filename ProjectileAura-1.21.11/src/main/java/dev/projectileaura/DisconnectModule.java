package dev.projectileaura;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

import java.util.Locale;

/**
 * Independent server watchdog. No /home commands, no SpawnReturn dependency.
 * Reconnects to the configured endpoint when disconnected or sent to a lobby.
 */
public class DisconnectModule extends Module {
    private static final long NS_PER_SECOND = 1_000_000_000L;

    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup detection = settings.createGroup("Redirect Detection");
    private final SettingGroup retry = settings.createGroup("Reconnect");

    private final Setting<String> targetServer = general.add(new StringSetting.Builder()
        .name("target-server")
        .description("Server to remain connected to, for example battlepet.minehut.gg. Blank disables recovery.")
        .defaultValue("").build());

    private final Setting<Boolean> detectOtherServer = detection.add(new BoolSetting.Builder()
        .name("detect-server-change")
        .description("Reconnect if the client shows a different server address than the target.")
        .defaultValue(true).build());

    private final Setting<Boolean> detectFallbackChat = detection.add(new BoolSetting.Builder()
        .name("detect-minehut-fallback-chat")
        .description("Treat known Minehut fallback/lobby messages as a redirect.")
        .defaultValue(true).build());

    private final Setting<String> lobbyScoreboard = detection.add(new StringSetting.Builder()
        .name("lobby-scoreboard-text")
        .description("Optional unique text in the Minehut lobby sidebar TITLE. Leave empty if unknown.")
        .defaultValue("").build());

    private final Setting<String> customRedirectMessage = detection.add(new StringSetting.Builder()
        .name("redirect-chat-text")
        .description("Optional additional case-insensitive chat phrase announcing a lobby redirect.")
        .defaultValue("").build());

    private final Setting<Double> connectTimeout = retry.add(new DoubleSetting.Builder()
        .name("connect-timeout-seconds")
        .description("Cancel an attempted connection if it does not join within this many seconds.")
        .defaultValue(10).min(1).sliderRange(3, 30).onSliderRelease().build());

    private final Setting<Double> retryDelay = retry.add(new DoubleSetting.Builder()
        .name("retry-delay-seconds")
        .description("Wait after a failed connection before trying again.")
        .defaultValue(30).min(1).sliderRange(5, 90).onSliderRelease().build());

    private final Setting<Double> verifySeconds = retry.add(new DoubleSetting.Builder()
        .name("verify-joined-seconds")
        .description("Allow lobby messages/scoreboards to appear before accepting a reconnect as successful.")
        .defaultValue(4).min(1).sliderRange(2, 15).onSliderRelease().build());

    private final Setting<Integer> maxAttempts = retry.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("Maximum connection attempts before stopping; 0 retries indefinitely.")
        .defaultValue(0).min(0).sliderRange(0, 30).build());

    private enum State { WATCHING, WAITING, CONNECTING, VERIFYING, STOPPED }
    private State state = State.WATCHING;
    private String savedTarget;
    private boolean redirectSeen;
    private long stateSince;
    private long nextAttemptAt;
    private int attempts;
    private String stopReason;

    public DisconnectModule() {
        super(ProjectileAuraAddon.CATEGORY, "disconnect",
            "Keep Minecraft on a selected server. Rejoin after disconnects or Minehut lobby redirects.");
        // Essential: TickEvent still runs while the Minecraft menu is displayed.
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        state = State.WATCHING;
        savedTarget = null;
        redirectSeen = false;
        attempts = 0;
        stateSince = 0;
        nextAttemptAt = 0;
        stopReason = null;
    }

    @EventHandler
    private void onChat(ReceiveMessageEvent event) {
        if (mc.level == null || mc.player == null || event.getMessage() == null) return;
        if (normalize(targetServer.get()) == null) return;

        String message = event.getMessage().getString().toLowerCase(Locale.ROOT);
        if (message.isBlank()) return;

        boolean common = detectFallbackChat.get()
            && (message.contains("fallback server")
                || message.contains("sent to a lobby")
                || message.contains("sent to the lobby")
                || message.contains("connected to a lobby")
                || message.contains("previously on went down"));

        String custom = customRedirectMessage.get().trim().toLowerCase(Locale.ROOT);
        if (common || (!custom.isEmpty() && message.contains(custom))) {
            redirectSeen = true;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.nanoTime();
        String configured = normalize(targetServer.get());

        // Empty or malformed destination: no attempts, no disconnects.
        if (configured == null) {
            if (state != State.WATCHING || savedTarget != null) reset();
            return;
        }

        // Changing the desired server re-arms the monitor and resets the retry count.
        if (!configured.equals(savedTarget)) {
            savedTarget = configured;
            redirectSeen = false;
            state = State.WATCHING;
            attempts = 0;
            nextAttemptAt = 0;
            stopReason = null;
        }

        if (state == State.STOPPED) return;

        boolean inWorld = mc.level != null && mc.player != null;
        if (inWorld) {
            processWorld(now, configured);
        } else {
            processMenus(now, configured);
        }
    }

    private void processWorld(long now, String expected) {
        String current = currentServerAddress();
        boolean wrongAddress = detectOtherServer.get() && current != null && !expected.equals(current);
        boolean lobby = redirectSeen || lobbySidebarDetected();

        if (wrongAddress || lobby) {
            // Wait a short moment to avoid racing world transitions or scoreboard changes.
            if (state != State.WAITING) {
                state = State.WAITING;
                stateSince = now;
            }
            if (now - stateSince >= seconds(2)) {
                // If the client has been transferred into the Minehut hub, close
                // that connection and rejoin the desired server, NOT the hub.
                redirectSeen = false;
                mc.disconnect(new TitleScreen(), false);
                nextAttemptAt = now + seconds(1);
                state = State.WAITING;
                stateSince = now;
            }
            return;
        }

        // The correct endpoint is connected. Do not trust an instant transition:
        // allow messages and sidebar to load before confirming success.
        if (state != State.WATCHING && state != State.VERIFYING) {
            state = State.VERIFYING;
            stateSince = now;
        } else if (state == State.VERIFYING && now - stateSince >= seconds(verifySeconds.get())) {
            state = State.WATCHING;
            attempts = 0;
            redirectSeen = false;
            stopReason = null;
        }
    }

    private void processMenus(long now, String expected) {
        if (state == State.CONNECTING) {
            if (mc.screen instanceof DisconnectedScreen) {
                scheduleRetry(now);
                return;
            }
            if (now - stateSince >= seconds(connectTimeout.get())) {
                cancelInFlightConnection();
                scheduleRetry(now);
            }
            return;
        }

        if (state == State.VERIFYING || state == State.WATCHING) {
            // We are no longer in a world. Try the target directly.
            state = State.WAITING;
            nextAttemptAt = now;
        }

        if (state == State.WAITING && now - nextAttemptAt >= 0L) {
            connect(now, expected);
        }
    }

    private void connect(long now, String expected) {
        if (maxAttempts.get() > 0 && attempts >= maxAttempts.get()) {
            stop("Retry limit reached");
            return;
        }

        // A GUI for an old connection may be open; cancel it before opening a new one.
        if (mc.screen instanceof ConnectScreen) cancelInFlightConnection();

        String addressText = targetServer.get().trim();
        if (!expected.equals(normalize(addressText))) {
            stop("Server address changed");
            return;
        }

        try {
            ServerAddress address = ServerAddress.parseString(addressText);
            ServerData info = new ServerData("ProjectileAura Target", addressText, ServerData.Type.OTHER);

            attempts++;
            state = State.CONNECTING;
            stateSince = now;
            redirectSeen = false;
            ConnectScreen.startConnecting(new TitleScreen(), mc, address, info, false, null);
        } catch (RuntimeException exception) {
            scheduleRetry(now);
        }
    }

    private void scheduleRetry(long now) {
        if (mc.screen instanceof ConnectScreen) cancelInFlightConnection();
        state = State.WAITING;
        nextAttemptAt = now + seconds(retryDelay.get());
        stateSince = now;
    }

    private void cancelInFlightConnection() {
        if (mc.screen instanceof ConnectScreen screen) {
            // ConnectScreen.onClose performs the same cancellation as Escape.
            screen.onClose();
            mc.setScreen(new TitleScreen());
        }
    }

    private boolean lobbySidebarDetected() {
        if (mc.level == null) return false;
        String marker = lobbyScoreboard.get().trim().toLowerCase(Locale.ROOT);
        if (marker.isEmpty()) return false;
        Objective sidebar = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        return sidebar != null && sidebar.getDisplayName().getString().toLowerCase(Locale.ROOT).contains(marker);
    }

    private String currentServerAddress() {
        ServerData server = mc.getCurrentServer();
        return server == null || server.isRealm() ? null : normalize(server.ip);
    }

    private static String normalize(String input) {
        if (input == null) return null;
        String raw = input.trim();
        if (raw.isEmpty() || raw.contains("/") || raw.contains("\\") || raw.contains(" ")
            || raw.contains("\r") || raw.contains("\n")) return null;
        try {
            if (!ServerAddress.isValidAddress(raw)) return null;
            ServerAddress parsed = ServerAddress.parseString(raw);
            return parsed.getHost().toLowerCase(Locale.ROOT) + ":" + parsed.getPort();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long seconds(double value) {
        if (!Double.isFinite(value)) return 30L * NS_PER_SECOND;
        return (long) (Math.max(0, Math.min(value, 3600)) * NS_PER_SECOND);
    }

    private void stop(String message) {
        state = State.STOPPED;
        stopReason = message;
    }

    @Override
    public String getInfoString() {
        return switch (state) {
            case WATCHING -> "Monitoring";
            case WAITING -> "Retry " + attempts;
            case CONNECTING -> "Connecting " + attempts;
            case VERIFYING -> "Verifying";
            case STOPPED -> stopReason == null ? "Stopped" : stopReason;
        };
    }
}
