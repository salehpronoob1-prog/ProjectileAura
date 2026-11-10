package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.ServerConnectBeginEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
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
 * Minehut recovery with two selectable lobby recovery strategies.
 * Uses the same ConnectScreen.startConnecting method as Meteor AutoReconnect.
 * Unrelated to SpawnReturn.
 */
public class DisconnectModule extends Module {
    private static final long SECOND = 1_000_000_000L;

    public enum LobbyAction {
        Reconnect,
        JoinCommand,
        ReconnectThenJoin
    }

    private enum State {
        WATCHING,
        WAITING_TO_CONNECT,
        CONNECTING
    }

    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup lobby = settings.createGroup("Minehut Lobby");
    private final SettingGroup reconnect = settings.createGroup("Reconnect Timing");

    private final Setting<String> targetServer = general.add(new StringSetting.Builder()
        .name("target-server")
        .description("Optional exact server address. Blank uses the address originally joined, just like Meteor.")
        .defaultValue("").build());

    private final Setting<String> lobbySidebar = lobby.add(new StringSetting.Builder()
        .name("lobby-sidebar")
        .description("Exact lobby sidebar title. Minehut lobby reports MINEHUT.")
        .defaultValue("MINEHUT").build());

    private final Setting<String> targetSidebar = lobby.add(new StringSetting.Builder()
        .name("target-sidebar")
        .description("Expected destination sidebar title, for example [BattlePet].")
        .defaultValue("[BattlePet]").build());

    private final Setting<LobbyAction> lobbyAction = lobby.add(new EnumSetting.Builder<LobbyAction>()
        .name("lobby-action")
        .description("Reconnect through Minecraft, use /join, or reconnect first and then /join.")
        .defaultValue(LobbyAction.ReconnectThenJoin).build());

    private final Setting<String> joinCommand = lobby.add(new StringSetting.Builder()
        .name("join-command")
        .description("Command to run ONLY while in the Minehut lobby.")
        .defaultValue("/join battlepet").build());

    private final Setting<Integer> fallbackAfter = lobby.add(new IntSetting.Builder()
        .name("reconnects-before-join")
        .description("In ReconnectThenJoin mode, switch to the lobby command after this many lobby reconnect attempts.")
        .defaultValue(2).min(1).sliderRange(1, 10)
        .visible(() -> lobbyAction.get() == LobbyAction.ReconnectThenJoin).build());

    private final Setting<Double> lobbyConfirm = lobby.add(new DoubleSetting.Builder()
        .name("lobby-confirm-seconds")
        .description("How long the MINEHUT sidebar must remain visible before acting.")
        .defaultValue(2.0).min(0.5).sliderRange(0.5, 10).decimalPlaces(1).onSliderRelease().build());

    private final Setting<Double> joinRetry = lobby.add(new DoubleSetting.Builder()
        .name("join-retry-seconds")
        .description("Minimum delay between /join attempts when the lobby sidebar remains.")
        .defaultValue(30.0).min(3).sliderRange(5, 90).decimalPlaces(1).onSliderRelease().build());

    private final Setting<Double> disconnectedDelay = reconnect.add(new DoubleSetting.Builder()
        .name("disconnected-delay-seconds")
        .description("Delay after getting disconnected before reconnecting (Meteor defaults to 3.5 seconds).")
        .defaultValue(3.5).min(0).sliderRange(0, 15).decimalPlaces(1).onSliderRelease().build());

    private final Setting<Double> connectionTimeout = reconnect.add(new DoubleSetting.Builder()
        .name("connect-timeout-seconds")
        .description("Cancel connection attempts lasting longer than this.")
        .defaultValue(10).min(2).sliderRange(3, 30).decimalPlaces(1).onSliderRelease().build());

    private final Setting<Double> retryDelay = reconnect.add(new DoubleSetting.Builder()
        .name("retry-delay-seconds")
        .description("Wait between failed connection attempts.")
        .defaultValue(30).min(1).sliderRange(5, 90).decimalPlaces(1).onSliderRelease().build());

    private ServerAddress savedAddress;
    private ServerData savedData;
    private boolean armed;
    private State state = State.WATCHING;
    private long nextConnectAt;
    private long connectSince;
    private long lobbySeenSince;
    private long lastJoinAt;
    private int lobbyReconnects;
    private int failedConnections;

    public DisconnectModule() {
        super(ProjectileAuraAddon.CATEGORY, "disconnect",
            "Reconnect after disconnections, and recover from the Minehut lobby using reconnect or /join.");
        // Keep listening and ticking on disconnected and multiplayer screens.
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        clear();
        // Allow enabling this module while already inside a multiplayer server.
        if (mc.level != null && mc.player != null && !mc.hasSingleplayerServer()) {
            saveCurrentServer();
            armed = true;
        }
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    private void clear() {
        savedAddress = null;
        savedData = null;
        armed = false;
        state = State.WATCHING;
        nextConnectAt = 0;
        connectSince = 0;
        lobbySeenSince = 0;
        lastJoinAt = 0;
        lobbyReconnects = 0;
        failedConnections = 0;
    }

    @EventHandler
    private void onConnectionBegin(ServerConnectBeginEvent event) {
        // Record manual joins, but never replace the destination during OUR retries.
        if (state != State.WATCHING || event.address == null || event.info == null) return;
        savedAddress = event.address;
        savedData = event.info;
        armed = true;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.nanoTime();

        if (mc.player != null && mc.level != null) {
            if (mc.hasSingleplayerServer()) {
                clear();  // Never interfere with singleplayer.
                return;
            }

            if (!armed) {
                saveCurrentServer();
                armed = true;
            }

            // A successful world connection ends the connection timeout.
            if (state == State.CONNECTING || state == State.WAITING_TO_CONNECT) {
                state = State.WATCHING;
                failedConnections = 0;
            }

            monitorWorld(now);
        } else if (armed) {
            monitorDisconnected(now);
        }
    }

    private void monitorWorld(long now) {
        String expected = intendedAddress();
        if (expected == null) return; // Do nothing without a valid target.

        String current = currentAddress();
        boolean wrongAddress = current != null && !current.equals(expected);

        String sidebar = currentSidebar();
        boolean inLobby = !lobbySidebar.get().isBlank()
            && sidebar.equalsIgnoreCase(lobbySidebar.get().trim());

        // Check a known-good sidebar, but never trigger recovery just because
        // the scoreboard is briefly absent during a dimension or proxy change.
        if (!inLobby && !wrongAddress) {
            lobbySeenSince = 0;
            if (!targetSidebar.get().isBlank() &&
                sidebar.equalsIgnoreCase(targetSidebar.get().trim())) {
                lobbyReconnects = 0;
                lastJoinAt = 0;
            }
            return;
        }

        if (lobbySeenSince == 0) lobbySeenSince = now;
        if (now - lobbySeenSince < seconds(lobbyConfirm.get())) return;

        if (wrongAddress) {
            reconnectFromWorld(now);
            return;
        }

        LobbyAction action = lobbyAction.get();
        boolean useJoin = action == LobbyAction.JoinCommand ||
            (action == LobbyAction.ReconnectThenJoin &&
                lobbyReconnects >= fallbackAfter.get());

        if (useJoin) {
            if (lastJoinAt == 0 || now - lastJoinAt >= seconds(joinRetry.get())) {
                String command = joinCommand.get().trim();
                if (command.startsWith("/")) command = command.substring(1);
                if (!command.isBlank() && !command.contains("\n") && !command.contains("\r")) {
                    ChatUtils.sendPlayerMsg("/" + command, false);
                    lastJoinAt = now;
                    info("Minehut lobby detected; sent (highlight)/%s(default).", command);
                }
            }
        } else {
            lobbyReconnects++;
            reconnectFromWorld(now);
        }
    }

    private void reconnectFromWorld(long now) {
        lobbySeenSince = 0;
        // Same general pattern as Meteor: disconnect, then startConnecting
        // using the original server address and ServerData.
        state = State.WAITING_TO_CONNECT;
        nextConnectAt = now + seconds(disconnectedDelay.get());
        mc.disconnect(new TitleScreen(), false);
    }

    private void monitorDisconnected(long now) {
        if (state == State.WATCHING) {
            // This also handles ordinary kicks/disconnects and failed manual joins.
            state = State.WAITING_TO_CONNECT;
            nextConnectAt = now + seconds(disconnectedDelay.get());
            return;
        }

        if (state == State.WAITING_TO_CONNECT) {
            if (now >= nextConnectAt) beginConnection(now);
            return;
        }

        if (state == State.CONNECTING) {
            if (mc.screen instanceof DisconnectedScreen) {
                planRetry(now);
            } else if (now - connectSince >= seconds(connectionTimeout.get())) {
                // Explicitly cancel a hung ConnectScreen before retrying.
                if (mc.screen instanceof ConnectScreen screen) screen.onClose();
                mc.setScreen(new TitleScreen());
                planRetry(now);
            }
        }
    }

    private void beginConnection(long now) {
        if (savedAddress == null || savedData == null) saveConfiguredTarget();
        if (savedAddress == null || savedData == null) {
            // Invalid setting: don't continually retry with an unknown server.
            armed = false;
            state = State.WATCHING;
            warning("No valid saved server. Join a server manually or configure target-server.");
            return;
        }

        String override = targetServer.get().trim();
        ServerAddress address = savedAddress;
        ServerData data = savedData;
        if (!override.isEmpty()) {
            try {
                if (!ServerAddress.isValidAddress(override)) throw new IllegalArgumentException();
                address = ServerAddress.parseString(override);
                data = new ServerData("ProjectileAura Target", override, ServerData.Type.OTHER);
            } catch (RuntimeException exception) {
                armed = false;
                state = State.WATCHING;
                warning("Invalid target-server setting.");
                return;
            }
        }

        state = State.CONNECTING;
        connectSince = now;
        redirectToTarget(address, data);
    }

    private void redirectToTarget(ServerAddress address, ServerData data) {
        // Exactly the public Minecraft connection API used by Meteor's AutoReconnect.
        ConnectScreen.startConnecting(new TitleScreen(), mc, address, data, false, null);
    }

    private void planRetry(long now) {
        failedConnections++;
        state = State.WAITING_TO_CONNECT;
        nextConnectAt = now + seconds(retryDelay.get());
    }

    private void saveCurrentServer() {
        ServerData data = mc.getCurrentServer();
        if (data == null || data.isRealm()) return;
        try {
            if (!ServerAddress.isValidAddress(data.ip)) return;
            savedAddress = ServerAddress.parseString(data.ip);
            savedData = data;
        } catch (RuntimeException ignored) { }
    }

    private void saveConfiguredTarget() {
        String override = targetServer.get().trim();
        if (override.isBlank()) return;
        try {
            if (!ServerAddress.isValidAddress(override)) return;
            savedAddress = ServerAddress.parseString(override);
            savedData = new ServerData("ProjectileAura Target", override, ServerData.Type.OTHER);
        } catch (RuntimeException ignored) { }
    }

    private String intendedAddress() {
        String override = targetServer.get().trim();
        if (!override.isEmpty()) return normalize(override);
        return savedData == null ? null : normalize(savedData.ip);
    }

    private String currentAddress() {
        ServerData server = mc.getCurrentServer();
        return server == null || server.isRealm() ? null : normalize(server.ip);
    }

    private String currentSidebar() {
        if (mc.level == null) return "";
        Objective sidebar = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        return sidebar == null ? "" : sidebar.getDisplayName().getString().trim();
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (!ServerAddress.isValidAddress(raw.trim())) return null;
            ServerAddress parsed = ServerAddress.parseString(raw.trim());
            return parsed.getHost().toLowerCase(Locale.ROOT) + ":" + parsed.getPort();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static long seconds(double value) {
        return (long) (Math.max(0, Math.min(value, 3600)) * SECOND);
    }

    @Override
    public String getInfoString() {
        String sidebar = currentSidebar();
        if (state == State.CONNECTING) return "Connecting";
        if (state == State.WAITING_TO_CONNECT) return "Retrying";
        if (sidebar.equalsIgnoreCase(lobbySidebar.get().trim())) return "MINEHUT lobby";
        return armed ? "Monitoring" : "Not armed";
    }
}
