package dev.projectileaura;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Experimental BattlePet Echo burrow solver.
 *
 * Uses packet-provided WORLD coordinates for flame particles, not the camera
 * direction or the player's rotation. Each visible Echo arrow supplies a
 * bearing. Two or more non-parallel bearings produce an X/Z intersection.
 *
 * Does NOT use a guessed random seed or claim that the server reveals distance.
 * A waypoint is an estimate until the actual burrow is independently verified.
 */
public class BurrowModule extends Module {
    private static final int MAX_QUEUE = 4096;
    private static final int MAX_BURST_POINTS = 2400;
    private static final int MAX_RAYS = 8;

    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup render = settings.createGroup("Render");

    private final Setting<Integer> minimumParticles = general.add(new IntSetting.Builder()
        .name("minimum-particles")
        .description("Minimum exact flame spawn coordinates required to accept an Echo arrow.")
        .defaultValue(18).min(6).sliderRange(6, 80).build());

    private final Setting<Integer> sampleRadius = general.add(new IntSetting.Builder()
        .name("sample-radius")
        .description("Only collect flame particles within this many blocks of your position.")
        .defaultValue(22).min(6).sliderRange(8, 32).build());

    private final Setting<Boolean> debug = general.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print accepted particle counts, bearings and rejected captures to chat.")
        .defaultValue(true).build());

    private final Setting<Boolean> showRays = render.add(new BoolSetting.Builder()
        .name("show-echo-directions")
        .description("Draw the measured directions of Echo arrows in world space.")
        .defaultValue(true).build());

    private final Setting<Boolean> showWaypoint = render.add(new BoolSetting.Builder()
        .name("show-estimated-burrow")
        .description("Mark the intersection of two or more Echo bearings.")
        .defaultValue(true).build());

    private final Setting<SettingColor> rayColor = render.add(new ColorSetting.Builder()
        .name("ray-color").defaultValue(new SettingColor(255, 165, 30, 230)).build());

    private final Setting<SettingColor> boxSideColor = render.add(new ColorSetting.Builder()
        .name("waypoint-side").defaultValue(new SettingColor(175, 65, 255, 40)).build());

    private final Setting<SettingColor> boxLineColor = render.add(new ColorSetting.Builder()
        .name("waypoint-line").defaultValue(new SettingColor(175, 65, 255, 230)).build());

    private record ParticlePoint(double x, double y, double z) {}
    private record EchoRay(double x, double y, double z, double dx, double dz) {}
    private record Estimate(double x, double z, double rmsError) {}

    // PacketEvent.Receive may execute off the client thread. Never read the
    // client world/player inside it; hand off position samples to TickEvent.
    private final ConcurrentLinkedQueue<ParticlePoint> incoming = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private volatile boolean worldReady;
    private volatile double playerX, playerY, playerZ;
    private volatile int captureRadius = 22;

    private final List<ParticlePoint> currentBurst = new ArrayList<>();
    private final List<EchoRay> rays = new ArrayList<>();
    private ClientLevel lastLevel;
    private int tick, burstStarted = -1, lastParticleTick, lastAcceptedTick = -1000;
    private double burstX, burstY, burstZ;
    private Estimate estimate;

    public BurrowModule() {
        super(ProjectileAuraAddon.CATEGORY, "burrow",
            "Experimental BattlePet Echo flame-arrow triangulation and estimated burrow waypoint.");
    }

    @Override
    public void onActivate() {
        reset();
        info("Burrow is listening for Echo flame arrows. Use Echo from two separated locations.");
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        worldReady = false;
        incoming.clear();
        queued.set(0);
        currentBurst.clear();
        rays.clear();
        estimate = null;
        lastLevel = null;
        tick = 0;
        burstStarted = -1;
        lastParticleTick = 0;
        lastAcceptedTick = -1000;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!worldReady || !(event.packet instanceof ClientboundLevelParticlesPacket packet)) return;
        ParticleType<?> type = packet.getParticle().getType();
        if (type != ParticleTypes.FLAME && type != ParticleTypes.SMALL_FLAME) return;

        // A particle packet with count=0 spawns exactly at getX/Y/Z. For count
        // 1 only trust a very small spread. Ignore randomized particle clouds.
        if (packet.getCount() > 1) return;
        if (packet.getCount() == 1 && (Math.abs(packet.getXDist()) > 0.15f
            || Math.abs(packet.getYDist()) > 0.15f || Math.abs(packet.getZDist()) > 0.15f)) return;

        double x = packet.getX(), y = packet.getY(), z = packet.getZ();
        double dx = x - playerX, dy = y - playerY, dz = z - playerZ;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || dx * dx + dz * dz > (double) captureRadius * captureRadius
            || Math.abs(dy) > 9) return;

        if (queued.incrementAndGet() > MAX_QUEUE) {
            queued.decrementAndGet();
            return;
        }
        incoming.add(new ParticlePoint(x, y, z));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) {
            if (lastLevel != null) reset();
            return;
        }
        if (lastLevel != mc.level) {
            reset();
            lastLevel = mc.level;
        }

        tick++;
        playerX = mc.player.getX();
        playerY = mc.player.getY();
        playerZ = mc.player.getZ();
        captureRadius = sampleRadius.get();
        worldReady = true;

        ParticlePoint point;
        while ((point = incoming.poll()) != null) {
            queued.decrementAndGet();
            if (burstStarted < 0) {
                burstStarted = tick;
                burstX = playerX;
                burstY = playerY;
                burstZ = playerZ;
            }
            if (currentBurst.size() < MAX_BURST_POINTS) currentBurst.add(point);
            lastParticleTick = tick;
        }

        // Wait for the short burst to end (or flush a long-running effect).
        if (burstStarted >= 0 && (tick - lastParticleTick >= 7 || tick - burstStarted >= 35)) {
            processBurst();
            currentBurst.clear();
            burstStarted = -1;
        }
    }

    private void processBurst() {
        if (tick - lastAcceptedTick < 25) return;
        if (currentBurst.size() < minimumParticles.get()) {
            if (debug.get() && !currentBurst.isEmpty())
                info("Only %d flame points (need %d); waiting for Echo.",
                    currentBurst.size(), minimumParticles.get());
            return;
        }

        // Echo draws a 3-pronged arrow. The arrow-tip is expected to be the
        // most distant point from its origin; averaging the outermost samples
        // reduces jitter. This assumption must be calibrated on BattlePet.
        List<ParticlePoint> points = new ArrayList<>();
        double maxRadiusSq = (double) sampleRadius.get() * sampleRadius.get();
        for (ParticlePoint p : currentBurst) {
            double dx = p.x - burstX, dz = p.z - burstZ;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq > 1.0 && distanceSq <= maxRadiusSq && Math.abs(p.y - burstY) <= 9) {
                points.add(p);
            }
        }
        if (points.size() < minimumParticles.get()) return;

        points.sort(Comparator.comparingDouble((ParticlePoint p) ->
            Math.pow(p.x - burstX, 2) + Math.pow(p.z - burstZ, 2)).reversed());

        int count = Math.min(12, Math.max(3, points.size() / 12));
        double tipX = 0, tipZ = 0;
        for (int i = 0; i < count; i++) {
            tipX += points.get(i).x;
            tipZ += points.get(i).z;
        }
        tipX /= count;
        tipZ /= count;
        double dx = tipX - burstX, dz = tipZ - burstZ;
        double length = Math.hypot(dx, dz);
        if (length < 3.0) {
            if (debug.get()) info("Echo flame cluster was too short for a bearing.");
            return;
        }

        dx /= length;
        dz /= length;
        EchoRay ray = new EchoRay(burstX, burstY, burstZ, dx, dz);

        // Repeat animations from almost the same origin must not create a
        // fake triangulation (two different player camera angles do not help).
        for (EchoRay old : rays) {
            if (Math.hypot(ray.x - old.x, ray.z - old.z) < 4.0) {
                if (debug.get()) info("Echo bearing captured, but move 5+ blocks for triangulation.");
                lastAcceptedTick = tick;
                return;
            }
        }

        if (rays.size() == MAX_RAYS) rays.remove(0);
        rays.add(ray);
        lastAcceptedTick = tick;

        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        info("Echo %d: %d flames, heading %.1f degrees (Minecraft yaw).",
            rays.size(), points.size(), yaw);

        Estimate next = triangulate();
        if (next != null) {
            estimate = next;
            info("Estimated burrow: X %.1f, Z %.1f (ray disagreement %.1f blocks).",
                estimate.x, estimate.z, estimate.rmsError);
        } else if (rays.size() > 1) {
            warning("Need a wider crossing angle or cleaner Echo trails; try another position.");
        }
    }

    private Estimate triangulate() {
        if (rays.size() < 2) return null;

        // Least-squares intersection of perpendicular distances to the rays.
        // For ray direction d=(dx,dz), its normal is n=(-dz,dx).
        double a = 0, b = 0, c = 0, bx = 0, bz = 0;
        for (EchoRay ray : rays) {
            double nx = -ray.dz, nz = ray.dx;
            double dot = nx * ray.x + nz * ray.z;
            a += nx * nx;
            b += nx * nz;
            c += nz * nz;
            bx += nx * dot;
            bz += nz * dot;
        }
        double det = a * c - b * b;
        if (det < 0.01) return null; // Near-parallel observations.

        double x = (bx * c - b * bz) / det;
        double z = (a * bz - b * bx) / det;
        if (!Double.isFinite(x) || !Double.isFinite(z)) return null;

        double errorSq = 0;
        for (EchoRay ray : rays) {
            double rx = x - ray.x, rz = z - ray.z;
            double forward = rx * ray.dx + rz * ray.dz;
            if (forward < 2 || forward > 500) return null;
            double lateral = -rx * ray.dz + rz * ray.dx;
            errorSq += lateral * lateral;
        }
        double rms = Math.sqrt(errorSq / rays.size());
        return rms <= 10 ? new Estimate(x, z, rms) : null;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (showRays.get()) {
            for (EchoRay ray : rays) {
                event.renderer.line(ray.x, ray.y + 0.5, ray.z,
                    ray.x + ray.dx * 90, ray.y + 0.5, ray.z + ray.dz * 90, rayColor.get());
            }
        }
        if (!showWaypoint.get() || estimate == null) return;

        double x = estimate.x, z = estimate.z;
        int blockX = (int) Math.floor(x), blockZ = (int) Math.floor(z);
        int y = mc.player.blockPosition().getY();
        if (mc.level.hasChunkAt(new BlockPos(blockX, y, blockZ))) {
            y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        }
        event.renderer.box(blockX, y, blockZ, blockX + 1, y + 1, blockZ + 1,
            boxSideColor.get(), boxLineColor.get(), ShapeMode.Both, 0);
        event.renderer.line(x, y + 1, z, x, y + 6, z, boxLineColor.get());
    }

    @Override
    public String getInfoString() {
        if (estimate != null) {
            return String.format(Locale.ROOT, "X %.0f Z %.0f (estimate)", estimate.x, estimate.z);
        }
        return rays.isEmpty() ? "Waiting for Echo" : rays.size() + " Echo direction(s)";
    }
}
