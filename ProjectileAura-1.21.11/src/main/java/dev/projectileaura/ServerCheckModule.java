package dev.projectileaura;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

/**
 * Read-only server diagnostics to compare Minehut lobby, Battlepet, and other servers.
 * This module NEVER connects, disconnects, sends commands or changes world state.
 */
public class ServerCheckModule extends Module {
    private static final long SECOND = 1_000_000_000L;
    private final SettingGroup reporting = settings.getDefaultGroup();

    private final Setting<Boolean> reportOnJoin = reporting.add(new BoolSetting.Builder()
        .name("report-on-join")
        .description("Print current server information after joining a world.")
        .defaultValue(true).build());

    private final Setting<Boolean> reportOnChange = reporting.add(new BoolSetting.Builder()
        .name("report-on-change")
        .description("Print again when server details change, including lobby scoreboard title.")
        .defaultValue(true).build());

    private final Setting<Double> minimumChangeInterval = reporting.add(new DoubleSetting.Builder()
        .name("change-report-cooldown-seconds")
        .description("Minimum time between automatic change reports, to avoid spam.")
        .defaultValue(10).min(2).sliderRange(2, 60).onSliderRelease().build());

    private boolean joined;
    private long firstJoinedAt;
    private long lastReportAt;
    private String lastReportedSignature;

    public ServerCheckModule() {
        super(ProjectileAuraAddon.CATEGORY, "server-check",
            "Display the raw server connection details; click to copy a comparison report.");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        joined = false;
        firstJoinedAt = 0;
        lastReportAt = 0;
        lastReportedSignature = null;
        if (isConnected()) {
            // Toggling the module is a manual request for a fresh report.
            reportNow();
            joined = true;
            firstJoinedAt = System.nanoTime();
        }
    }

    @Override
    public void onDeactivate() {
        joined = false;
        lastReportedSignature = null;
    }

    private boolean isConnected() {
        return mc.player != null && mc.level != null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isConnected()) {
            joined = false;
            firstJoinedAt = 0;
            lastReportedSignature = null;
            return;
        }

        long now = System.nanoTime();
        if (!joined) {
            joined = true;
            firstJoinedAt = now;
            lastReportedSignature = null;
            return;
        }

        // Wait briefly after joining so the server brand/scoreboard can populate.
        if (lastReportedSignature == null) {
            if (now - firstJoinedAt >= SECOND) {
                if (reportOnJoin.get()) reportNow();
                else lastReportedSignature = signature();
            }
            return;
        }

        if (reportOnChange.get() &&
            !signature().equals(lastReportedSignature) &&
            now - lastReportAt >= (long) (minimumChangeInterval.get() * SECOND)) {
            reportNow();
        }
    }

    /**
     * Returns the exact diagnostic string printed/copyable in-game.
     * The address may still be the same after an internal proxy transfer.
     */
    public static String getServerInformation() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return "Status: not in a world";

        ServerData server = client.getCurrentServer();
        if (client.hasSingleplayerServer()) {
            return "Status: singleplayer\nDimension: " + clean(client.level.dimension().toString());
        }

        String name = server == null ? "<unavailable>" : clean(server.name);
        String address = server == null ? "<unavailable>" : clean(server.ip);
        String brand = "<unavailable>";
        String socket = "<unavailable>";
        if (client.getConnection() != null) {
            if (client.getConnection().serverBrand() != null)
                brand = clean(client.getConnection().serverBrand());
            if (client.getConnection().getConnection() != null &&
                client.getConnection().getConnection().getRemoteAddress() != null)
                socket = clean(client.getConnection().getConnection().getRemoteAddress().toString());
        }

        String sidebar = "<none>";
        Objective objective = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective != null) sidebar = clean(objective.getDisplayName().getString());

        String dimension = clean(client.level.dimension().toString());
        String motd = server == null || server.motd == null ? "<unavailable>" : clean(server.motd.getString());
        String version = server == null || server.version == null ? "<unavailable>" : clean(server.version.getString());

        return "Saved server name: " + name
            + "\nSaved server IP: " + address
            + "\nRemote socket: " + socket
            + "\nServer brand: " + brand
            + "\nSidebar title: " + sidebar
            + "\nDimension: " + dimension
            + "\nServer version: " + version
            + "\nSaved list MOTD: " + motd;
    }

    private static String clean(String input) {
        if (input == null || input.isBlank()) return "<empty>";
        // Keep the returned values readable and avoid spoofed line breaks in chat.
        String value = input.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return value.length() > 220 ? value.substring(0, 220) + "..." : value;
    }

    private String signature() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return "disconnected";
        ServerData server = client.getCurrentServer();
        Objective sidebar = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        String title = sidebar == null ? "" : sidebar.getDisplayName().getString();
        String brand = client.getConnection() == null ? ""
            : String.valueOf(client.getConnection().serverBrand());
        String socket = client.getConnection() == null || client.getConnection().getConnection() == null
            ? "" : String.valueOf(client.getConnection().getConnection().getRemoteAddress());
        return (server == null ? "singleplayer" : server.ip)
            + "|" + brand + "|" + socket + "|" + title
            + "|" + client.level.dimension();
    }

    public void reportNow() {
        if (!isConnected()) return;
        String result = getServerInformation();
        lastReportedSignature = signature();
        lastReportAt = System.nanoTime();

        info("Server information (read-only):");
        for (String line : result.split("\\n")) {
            info("%s", line);
        }

        Component copy = Component.literal("[ServerCheck] Click to copy the complete report")
            .setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.CopyToClipboard(result))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy raw server diagnostic string"))));
        ChatUtils.sendMsg(copy);
    }

    @Override
    public String getInfoString() {
        ServerData server = mc.getCurrentServer();
        return server == null ? "No server" : server.ip;
    }
}
