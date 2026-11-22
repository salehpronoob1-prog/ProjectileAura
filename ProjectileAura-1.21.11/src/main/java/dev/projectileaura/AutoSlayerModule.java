package dev.projectileaura;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Activates the [Click] run-command component of BattlePet's Slayer XP message.
 *
 * This reads the actual server-provided click event, not a guessed command or
 * a screen coordinate. The packet listener may run off the render thread, so
 * command dispatch is deferred to a client tick.
 */
public class AutoSlayerModule extends Module {
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int MAX_QUEUED_CLICKS = 8;

    private final SettingGroup general = settings.getDefaultGroup();

    private final Setting<String> triggerText = general.add(new StringSetting.Builder()
        .name("trigger-text")
        .description("Only react when the incoming chat message contains this phrase.")
        .defaultValue("Speak to Xoddam to claim your Slayer XP!")
        .build());

    private final Setting<String> clickableText = general.add(new StringSetting.Builder()
        .name("clickable-text")
        .description("Only activate a RUN_COMMAND click event attached to this text.")
        .defaultValue("Click")
        .build());

    private final Setting<Boolean> caseSensitive = general.add(new BoolSetting.Builder()
        .name("case-sensitive")
        .description("Require exact capitalization for both text filters.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> clickDelayMs = general.add(new IntSetting.Builder()
        .name("click-delay-ms")
        .description("Delay after receiving the matching message (0 = next client tick).")
        .defaultValue(0).min(0).sliderRange(0, 2000)
        .build());

    private final Setting<Integer> cooldownMs = general.add(new IntSetting.Builder()
        .name("cooldown-ms")
        .description("Minimum time between activations; duplicate messages during cooldown are ignored.")
        .defaultValue(500).min(0).sliderRange(0, 5000)
        .build());

    private record PendingClick(String command, long readyAtNanos) {}

    private final ConcurrentLinkedQueue<PendingClick> pendingClicks = new ConcurrentLinkedQueue<>();
    private long nextAllowedAtNanos;

    public AutoSlayerModule() {
        super(ProjectileAuraAddon.CATEGORY, "auto-slayer",
            "Automatically activate BattlePet's Slayer XP [Click] chat message.");
    }

    @Override
    public void onActivate() {
        pendingClicks.clear();
        nextAllowedAtNanos = 0;
    }

    @Override
    public void onDeactivate() {
        pendingClicks.clear();
        nextAllowedAtNanos = 0;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Component message;

        if (event.packet instanceof ClientboundSystemChatPacket packet) {
            if (packet.overlay()) return; // Ignore action-bar messages.
            message = packet.content();
        } else if (event.packet instanceof ClientboundDisguisedChatPacket packet) {
            message = packet.message();
        } else {
            return;
        }

        String trigger = triggerText.get().trim();
        String label = clickableText.get().trim();
        boolean exactCase = caseSensitive.get();
        if (trigger.isEmpty() || label.isEmpty()
            || !contains(message.getString(), trigger, exactCase)) return;

        // The label and its inherited style must belong to the actual
        // clickable component. Never click an unrelated action in the line.
        Optional<String> command = message.visit((style, text) -> {
            if (!contains(text, label, exactCase)) return Optional.empty();
            if (style.getClickEvent() instanceof ClickEvent.RunCommand runCommand)
                return Optional.of(runCommand.command());
            return Optional.empty();
        }, Style.EMPTY);

        if (command.isEmpty()) return;
        String raw = command.get().trim();
        if (raw.isEmpty() || raw.length() > 256 || raw.indexOf('\n') >= 0
            || raw.indexOf('\r') >= 0 || raw.indexOf('\0') >= 0) return;

        if (pendingClicks.size() >= MAX_QUEUED_CLICKS) return;
        pendingClicks.offer(new PendingClick(raw,
            System.nanoTime() + clickDelayMs.get() * NANOS_PER_MILLI));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.player.connection == null
            || mc.player.isDeadOrDying()) {
            pendingClicks.clear();
            return;
        }

        long now = System.nanoTime();
        PendingClick pending;
        while ((pending = pendingClicks.peek()) != null) {
            if (now - pending.readyAtNanos() < 0) return;

            pendingClicks.poll();
            if (now - nextAllowedAtNanos < 0) continue;

            // Same command dispatch as a RUN_COMMAND chat click, without
            // opening chat, moving the mouse, or printing a local message.
            ChatUtils.sendPlayerMsg(pending.command(), false);
            nextAllowedAtNanos = now + cooldownMs.get() * NANOS_PER_MILLI;
            return; // Never send more than one action per client tick.
        }
    }

    private static boolean contains(String text, String term, boolean exactCase) {
        if (term.isEmpty()) return false;
        return exactCase ? text.contains(term)
            : text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    @Override
    public String getInfoString() {
        return "Listening";
    }
}
